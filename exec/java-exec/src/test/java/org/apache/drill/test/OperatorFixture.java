/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.common.scanner.ClassPathScanner;
import org.apache.drill.common.scanner.persistence.ScanResult;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.compile.CodeCompiler;
import org.apache.drill.exec.exception.ClassTransformationException;
import org.apache.drill.exec.expr.ClassGenerator;
import org.apache.drill.exec.expr.CodeGenerator;
import org.apache.drill.exec.expr.fn.FunctionImplementationRegistry;
import org.apache.drill.exec.memory.RootAllocatorFactory;
import org.apache.drill.exec.ops.AbstractOperatorExecContext;
import org.apache.drill.exec.ops.FragmentExecContext;
import org.apache.drill.exec.ops.MetricDef;
import org.apache.drill.exec.ops.OperExecContext;
import org.apache.drill.exec.ops.OperExecContextImpl;
import org.apache.drill.exec.ops.OperatorExecContext;
import org.apache.drill.exec.ops.OperatorStatReceiver;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.TupleMetadata;
import org.apache.drill.exec.record.TupleSchema;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.selection.SelectionVector2;
import org.apache.drill.exec.server.options.OptionSet;
import org.apache.drill.exec.server.options.SystemOptionManager;
import org.apache.drill.exec.testing.ExecutionControls;
import org.apache.drill.test.ClusterFixtureBuilder.RuntimeOption;
import org.apache.drill.test.rowSet.DirectRowSet;
import org.apache.drill.test.rowSet.HyperRowSetImpl;
import org.apache.drill.test.rowSet.IndirectRowSet;
import org.apache.drill.test.rowSet.RowSet;
import org.apache.drill.test.rowSet.RowSet.ExtendableRowSet;
import org.apache.drill.test.rowSet.RowSetBuilder;

/**
 * Test fixture for operator and (especially) "sub-operator" tests.
 * These are tests that are done without the full Drillbit server.
 * Instead, this fixture creates a test fixture runtime environment
 * that provides "real" implementations of the classes required by
 * operator internals, but with implementations tuned to the test
 * environment. The services available from this fixture are:
 * <ul>
 * <li>Configuration (DrillConfig)</li>
 * <li>Memory allocator</li>
 * <li>Code generation (compilers, code cache, etc.)</li>
 * <li>Read-only version of system and session options (which
 * are set when creating the fixture.</li>
 * <li>Write-only version of operator stats (which are easy to
 * read to verify in tests.</li>
 * </ul>
 * What is <b>not</b> provided is anything that depends on a live server:
 * <ul>
 * <li>Network endpoints.</li>
 * <li>Persistent storage.</li>
 * <li>ZK access.</li>
 * <li>Multiple threads of execution.</li>
 * </ul>
 */

public class OperatorFixture extends BaseFixture implements AutoCloseable {

  /**
   * Builds an operator fixture based on a set of config options and system/session
   * options.
   */

  public static class OperatorFixtureBuilder
  {
    protected ConfigBuilder configBuilder = new ConfigBuilder();
    protected List<RuntimeOption> systemOptions;
    protected ExecutionControls controls;

    public ConfigBuilder configBuilder() {
      return configBuilder;
    }

    public OperatorFixtureBuilder systemOption(String key, Object value) {
      if (systemOptions == null) {
        systemOptions = new ArrayList<>();
      }
      systemOptions.add(new RuntimeOption(key, value));
      return this;
    }

    public OperatorFixtureBuilder setControls(ExecutionControls controls) {
      this.controls = controls;
      return this;
    }

    public OperatorFixture build() {
      return new OperatorFixture(this);
    }
  }

  /**
   * Provide a simplified test-time code generation context that
   * uses the same code generation mechanism as the full Drill, but
   * provide test-specific versions of various other services.
   */

  public static class TestCodeGenContext implements FragmentExecContext {

    private final DrillConfig config;
    private final OptionSet options;
    private final CodeCompiler compiler;
    private final FunctionImplementationRegistry functionRegistry;
    private ExecutionControls controls;

    public TestCodeGenContext(DrillConfig config, OptionSet options) {
      this.config = config;
      this.options = options;
      ScanResult classpathScan = ClassPathScanner.fromPrescan(config);
      functionRegistry = new FunctionImplementationRegistry(config, classpathScan, options);
      compiler = new CodeCompiler(config, options);
    }

    public void setExecutionControls(ExecutionControls controls) {
      this.controls = controls;
    }

    @Override
    public FunctionImplementationRegistry getFunctionRegistry() {
      return functionRegistry;
    }

    @Override
    public OptionSet getOptionSet() {
      return options;
    }

    @Override
    public <T> T getImplementationClass(final ClassGenerator<T> cg)
        throws ClassTransformationException, IOException {
      return getImplementationClass(cg.getCodeGenerator());
    }

    @Override
    public <T> T getImplementationClass(final CodeGenerator<T> cg)
        throws ClassTransformationException, IOException {
      return compiler.createInstance(cg);
    }

    @Override
    public <T> List<T> getImplementationClass(final ClassGenerator<T> cg, final int instanceCount) throws ClassTransformationException, IOException {
      return getImplementationClass(cg.getCodeGenerator(), instanceCount);
    }

    @Override
    public <T> List<T> getImplementationClass(final CodeGenerator<T> cg, final int instanceCount) throws ClassTransformationException, IOException {
      return compiler.createInstances(cg, instanceCount);
    }

    @Override
    public boolean shouldContinue() {
      return true;
    }

    @Override
    public ExecutionControls getExecutionControls() {
      return controls;
    }

    @Override
    public DrillConfig getConfig() {
      return config;
    }
  }

  /**
   * Implements a write-only version of the stats collector for use by operators,
   * then provides simplified test-time accessors to get the stats values when
   * validating code in tests.
   */

  public static class MockStats implements OperatorStatReceiver {

    public Map<Integer, Double> stats = new HashMap<>();

    @Override
    public void addLongStat(MetricDef metric, long value) {
      setStat(metric, getStat(metric) + value);
    }

    @Override
    public void addDoubleStat(MetricDef metric, double value) {
      setStat(metric, getStat(metric) + value);
    }

    @Override
    public void setLongStat(MetricDef metric, long value) {
      setStat(metric, value);
    }

    @Override
    public void setDoubleStat(MetricDef metric, double value) {
      setStat(metric, value);
    }

    public double getStat(MetricDef metric) {
      return getStat(metric.metricId());
    }

    private double getStat(int metricId) {
      Double value = stats.get(metricId);
      return value == null ? 0 : value;
    }

    private void setStat(MetricDef metric, double value) {
      setStat(metric.metricId(), value);
    }

    private void setStat(int metricId, double value) {
      stats.put(metricId, value);
    }
  }

  private final SystemOptionManager options;
  private final TestCodeGenContext context;
  private final OperatorStatReceiver stats;

  protected OperatorFixture(OperatorFixtureBuilder builder) {
    config = builder.configBuilder().build();
    allocator = RootAllocatorFactory.newRoot(config);
    options = new SystemOptionManager(config);
    try {
      options.init();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to initialize the system option manager", e);
    }
    if (builder.systemOptions != null) {
      applySystemOptions(builder.systemOptions);
    }
    context = new TestCodeGenContext(config, options);
    stats = new MockStats();
   }

  private void applySystemOptions(List<RuntimeOption> systemOptions) {
    for (RuntimeOption option : systemOptions) {
      options.setLocalOption(option.key, option.value);
    }
  }

  public SystemOptionManager options() { return options; }
  public FragmentExecContext fragmentExecContext() { return context; }

  @Override
  public void close() throws Exception {
    allocator.close();
    options.close();
  }

  public static OperatorFixtureBuilder builder() {
    OperatorFixtureBuilder builder = new OperatorFixtureBuilder();
    builder.configBuilder()
      // Required to avoid Dynamic UDF calls for missing or
      // ambiguous functions.
      .put(ExecConstants.UDF_DISABLE_DYNAMIC, true);
    return builder;
  }

  public static OperatorFixture standardFixture() {
    return builder().build();
  }

  public OperExecContext newOperExecContext(PhysicalOperator opDefn) {
    return new OperExecContextImpl(context, allocator, stats, opDefn, null);
  }

  public RowSetBuilder rowSetBuilder(BatchSchema schema) {
    return rowSetBuilder(TupleSchema.fromFields(schema));
  }

  public RowSetBuilder rowSetBuilder(TupleMetadata schema) {
    return new RowSetBuilder(allocator, schema);
  }

  public ExtendableRowSet rowSet(BatchSchema schema) {
    return DirectRowSet.fromSchema(allocator, schema);
  }

  public ExtendableRowSet rowSet(TupleMetadata schema) {
    return DirectRowSet.fromSchema(allocator, schema);
  }

  public RowSet wrap(VectorContainer container) {
    switch (container.getSchema().getSelectionVectorMode()) {
    case FOUR_BYTE:
      return new HyperRowSetImpl(container, container.getSelectionVector4());
    case NONE:
      return DirectRowSet.fromContainer(container);
    case TWO_BYTE:
      return IndirectRowSet.fromSv2(container, container.getSelectionVector2());
    default:
      throw new IllegalStateException( "Unexpected selection mode" );
    }
  }

  public RowSet wrap(VectorContainer container, SelectionVector2 sv2) {
    if (sv2 == null) {
      assert container.getSchema().getSelectionVectorMode() == SelectionVectorMode.NONE;
      return DirectRowSet.fromContainer(container);
    } else {
      assert container.getSchema().getSelectionVectorMode() == SelectionVectorMode.TWO_BYTE;
      return IndirectRowSet.fromSv2(container, sv2);
    }
  }

  public OperatorExecContext operatorContext(PhysicalOperator config) {
    return new AbstractOperatorExecContext(allocator(), config, context.getExecutionControls(), stats);
  }
}
