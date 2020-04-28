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
package org.apache.drill.exec.store.dfs.easy;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.logical.FormatPluginConfig;
import org.apache.drill.common.logical.StoragePluginConfig;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.base.AbstractGroupScan;
import org.apache.drill.exec.physical.base.AbstractWriter;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.physical.base.ScanStats;
import org.apache.drill.exec.physical.base.ScanStats.GroupScanProperty;
import org.apache.drill.exec.physical.impl.ScanBatch;
import org.apache.drill.exec.physical.impl.StatisticsWriterRecordBatch;
import org.apache.drill.exec.physical.impl.WriterRecordBatch;
import org.apache.drill.exec.physical.impl.scan.file.FileScanFramework.FileScanBuilder;
import org.apache.drill.exec.physical.impl.scan.file.FileScanFramework.FileSchemaNegotiator;
import org.apache.drill.exec.physical.impl.scan.framework.ManagedReader;
import org.apache.drill.exec.physical.impl.scan.v3.file.FileScanLifecycleBuilder;
import org.apache.drill.exec.planner.common.DrillStatsTable.TableStatistics;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.apache.drill.exec.record.CloseableRecordBatch;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.server.DrillbitContext;
import org.apache.drill.exec.server.options.OptionManager;
import org.apache.drill.exec.store.RecordReader;
import org.apache.drill.exec.store.RecordWriter;
import org.apache.drill.exec.store.StatisticsRecordWriter;
import org.apache.drill.exec.store.StoragePluginOptimizerRule;
import org.apache.drill.exec.store.dfs.BasicFormatMatcher;
import org.apache.drill.exec.store.dfs.DrillFileSystem;
import org.apache.drill.exec.store.dfs.FileSelection;
import org.apache.drill.exec.store.dfs.FormatMatcher;
import org.apache.drill.exec.store.dfs.FormatPlugin;
import org.apache.drill.exec.store.schedule.CompleteFileWork;
import org.apache.drill.exec.metastore.MetadataProviderManager;
import org.apache.drill.shaded.guava.com.google.common.collect.ImmutableSet;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Base class for file format plugins.
 *
 * @param <T> the format plugin config for this reader
 */
public abstract class EasyFormatPlugin<T extends FormatPluginConfig> implements FormatPlugin {

  public enum ScanFrameworkVersion {
    CLASSIC,
    EVF_V1,
    EVF_V2
  }

  /**
   * Defines the static, programmer-defined options for this plugin. These
   * options are attributes of how the plugin works. The plugin config,
   * defined in the class definition, provides user-defined options that can
   * vary across uses of the plugin.
   */
  public static class EasyFormatConfig {

    public BasicFormatMatcher matcher;
    public boolean readable = true;
    public boolean writable;
    public boolean blockSplittable;
    public boolean compressible;
    public Configuration fsConf;
    public List<String> extensions;
    public String defaultName;

    // Config options that, prior to Drill 1.15, required the plugin to
    // override methods. Moving forward, plugins should be migrated to
    // use this simpler form. New plugins should use these options
    // instead of overriding methods.

    public boolean supportsProjectPushdown;
    public boolean supportsFileImplicitColumns = true;
    public boolean supportsAutoPartitioning;
    public boolean supportsStatistics;
    public int readerOperatorType = -1;
    public int writerOperatorType = -1;

    /**
     * Choose whether to use the "traditional" or "enhanced" reader
     * structure. Can also be selected at runtime by overriding
     * {@link #useEnhancedScan(OptionManager)}.
     */
    public ScanFrameworkVersion scanVersion = ScanFrameworkVersion.CLASSIC;
  }

  private final String name;
  private final EasyFormatConfig easyConfig;
  private final DrillbitContext context;
  private final StoragePluginConfig storageConfig;
  protected final T formatConfig;

  /**
   * Legacy constructor.
   */
  protected EasyFormatPlugin(String name, DrillbitContext context, Configuration fsConf,
      StoragePluginConfig storageConfig, T formatConfig, boolean readable, boolean writable,
      boolean blockSplittable,
      boolean compressible, List<String> extensions, String defaultName) {
    this.name = name == null ? defaultName : name;
    easyConfig = new EasyFormatConfig();
    easyConfig.matcher = new BasicFormatMatcher(this, fsConf, extensions, compressible);
    easyConfig.readable = readable;
    easyConfig.writable = writable;
    this.context = context;
    easyConfig.blockSplittable = blockSplittable;
    easyConfig.compressible = compressible;
    easyConfig.fsConf = fsConf;
    this.storageConfig = storageConfig;
    this.formatConfig = formatConfig;
  }

  /**
   * Revised constructor in which settings are gathered into a configuration object.
   *
   * @param name name of the plugin
   * @param config configuration options for this plugin which determine
   * developer-defined runtime behavior
   * @param context the global server-wide Drillbit context
   * @param storageConfig the configuration for the storage plugin that owns this
   * format plugin
   * @param formatConfig the Jackson-serialized format configuration as created
   * by the user in the Drill web console. Holds user-defined options
   */
  protected EasyFormatPlugin(String name, EasyFormatConfig config, DrillbitContext context,
      StoragePluginConfig storageConfig, T formatConfig) {
    this.name = name;
    this.easyConfig = config;
    this.context = context;
    this.storageConfig = storageConfig;
    this.formatConfig = formatConfig;
    if (easyConfig.matcher == null) {
      easyConfig.matcher = new BasicFormatMatcher(this,
          easyConfig.fsConf, easyConfig.extensions,
          easyConfig.compressible);
    }
  }

  @Override
  public Configuration getFsConf() { return easyConfig.fsConf; }

  @Override
  public DrillbitContext getContext() { return context; }

  public EasyFormatConfig easyConfig() { return easyConfig; }

  @Override
  public String getName() { return name; }

  /**
   * Does this plugin support projection push down? That is, can the reader
   * itself handle the tasks of projecting table columns, creating null
   * columns for missing table columns, and so on?
   *
   * @return {@code true} if the plugin supports projection push-down,
   * {@code false} if Drill should do the task by adding a project operator
   */
  public boolean supportsPushDown() { return easyConfig.supportsProjectPushdown; }

  /**
   * Whether this format plugin supports implicit file columns.
   *
   * @return {@code true} if the plugin supports implicit file columns,
   * {@code false} otherwise
   */
  public boolean supportsFileImplicitColumns() {
    return easyConfig.supportsFileImplicitColumns;
  }

  /**
   * Whether or not you can split the format based on blocks within file
   * boundaries. If not, the simple format engine will only split on file
   * boundaries.
   *
   * @return {@code true} if splitable.
   */
  public boolean isBlockSplittable() { return easyConfig.blockSplittable; }

  /**
   * Indicates whether or not this format could also be in a compression
   * container (for example: csv.gz versus csv). If this format uses its own
   * internal compression scheme, such as Parquet does, then this should return
   * false.
   *
   * @return {@code true} if it is compressible
   */
  public boolean isCompressible() { return easyConfig.compressible; }

  /**
   * Return a record reader for the specific file format, when using the original
   * {@link ScanBatch} scanner.
   * @param context fragment context
   * @param dfs Drill file system
   * @param fileWork metadata about the file to be scanned
   * @param columns list of projected columns (or may just contain the wildcard)
   * @param userName the name of the user running the query
   * @return a record reader for this format
   * @throws ExecutionSetupException for many reasons
   */
  public RecordReader getRecordReader(FragmentContext context, DrillFileSystem dfs, FileWork fileWork,
      List<SchemaPath> columns, String userName) throws ExecutionSetupException {
    throw new ExecutionSetupException("Must implement getRecordReader() if using the legacy scanner.");
  }

  protected CloseableRecordBatch getReaderBatch(FragmentContext context,
      EasySubScan scan) throws ExecutionSetupException {
    try {
      switch (scanVersion(context.getOptions())) {
      case CLASSIC:
        return buildScanBatch(context, scan);
      case EVF_V1:
        return buildScan(context, scan);
      case EVF_V2:
        return buildScanV3(context, scan);
      default:
        throw new IllegalStateException("Unknown scan version");
      }
    } catch (final UserException e) {
      // Rethrow user exceptions directly
      throw e;
    } catch (final ExecutionSetupException e) {
      throw e;
    } catch (final Throwable e) {
      // Wrap all others
      throw new ExecutionSetupException(e);
    }
  }

  /**
   * Choose whether to use the enhanced scan based on the row set and scan
   * framework, or the "traditional" ad-hoc structure based on ScanBatch.
   * Normally set as a config option. Override this method if you want to
   * make the choice based on a system/session option.
   *
   * @return true to use the enhanced scan framework, false for the
   * traditional scan-batch framework
   */
  protected ScanFrameworkVersion scanVersion(OptionManager options) {
    return easyConfig.scanVersion;
  }

  /**
   * Use the original scanner based on the {@link RecordReader} interface.
   * Requires that the storage plugin roll its own solutions for null columns.
   * Is not able to limit vector or batch sizes. Retained or backward
   * compatibility with "classic" format plugins which have not yet been
   * upgraded to use the new framework.
   */
  private CloseableRecordBatch buildScanBatch(FragmentContext context,
      EasySubScan scan) throws ExecutionSetupException {
    return new ClassicScanBuilder(context, scan, this).build();
  }

  /**
   * Revised scanner based on the revised {@link org.apache.drill.exec.physical.resultSet.ResultSetLoader}
   * and {@link org.apache.drill.exec.physical.impl.scan.RowBatchReader} classes.
   * Handles most projection tasks automatically. Able to limit
   * vector and batch sizes. Use this for new format plugins.
   */
  private CloseableRecordBatch buildScan(FragmentContext context,
      EasySubScan scan) throws ExecutionSetupException {
    return new EvfV1ScanBuilder(context, scan, this).build();
  }

  private CloseableRecordBatch buildScanV3(FragmentContext context,
      EasySubScan scan) throws ExecutionSetupException {
    EasyFileScanBuilder builder = new EasyFileScanBuilder(context, scan, this);
    configureScan(builder, scan);
    return builder.buildScanOperator(context, scan);
  }

  /**
   * Configure an EVF (v2) scan, which must at least include the factory to
   * create readers.
   *
   * @param builder the builder with default options already set, and which
   * allows the plugin implementation to set others
   */
  protected void configureScan(FileScanLifecycleBuilder builder, EasySubScan scan) {
    throw new UnsupportedOperationException("Implement this method if you use EVF V2");
  }

  /**
   * Initialize the scan framework builder with standard options.
   * Call this from the plugin-specific
   * {@link #frameworkBuilder(OptionManager, EasySubScan)} method.
   * The plugin can then customize/revise options as needed.
   * <p>
   * For EVF V1, to be removed.
   *
   * @param builder the scan framework builder you create in the
   * {@link #frameworkBuilder(OptionManager, EasySubScan)} method
   * @param scan the physical scan operator definition passed to
   * the {@link #frameworkBuilder(OptionManager, EasySubScan)} method
   */
  protected void initScanBuilder(FileScanBuilder builder, EasySubScan scan) {
    EvfV1ScanBuilder.initScanBuilder(this, builder, scan);
  }

  /**
   * For EVF V1, to be removed.
   */
  public ManagedReader<? extends FileSchemaNegotiator> newBatchReader(
      EasySubScan scan, OptionManager options) throws ExecutionSetupException {
    throw new ExecutionSetupException("Must implement newBatchReader() if using the enhanced framework.");
  }

  /**
   * Create the plugin-specific framework that manages the scan. The framework
   * creates batch readers one by one for each file or block. It defines semantic
   * rules for projection. It handles "early" or "late" schema readers. A typical
   * framework builds on standardized frameworks for files in general or text
   * files in particular.
   * <p>
   * For EVF V1, to be removed.
   *
   * @param scan the physical operation definition for the scan operation. Contains
   * one or more files to read. (The Easy format plugin works only for files.)
   * @return the scan framework which orchestrates the scan operation across
   * potentially many files
   * @throws ExecutionSetupException for all setup failures
   */
  protected FileScanBuilder frameworkBuilder(
      OptionManager options, EasySubScan scan) throws ExecutionSetupException {
    throw new ExecutionSetupException("Must implement frameworkBuilder() if using the enhanced framework.");
  }

  public boolean isStatisticsRecordWriter(FragmentContext context, EasyWriter writer) {
    return false;
  }

  public RecordWriter getRecordWriter(FragmentContext context,
                                      EasyWriter writer) throws IOException {
    throw new UnsupportedOperationException("unimplemented");
  }

  public StatisticsRecordWriter getStatisticsRecordWriter(FragmentContext context,
      EasyWriter writer) throws IOException {
    return null;
  }

  public CloseableRecordBatch getWriterBatch(FragmentContext context, RecordBatch incoming, EasyWriter writer)
      throws ExecutionSetupException {
    try {
      if (isStatisticsRecordWriter(context, writer)) {
        return new StatisticsWriterRecordBatch(writer, incoming, context, getStatisticsRecordWriter(context, writer));
      } else {
        return new WriterRecordBatch(writer, incoming, context, getRecordWriter(context, writer));
      }
    } catch(IOException e) {
      throw new ExecutionSetupException(String.format("Failed to create the WriterRecordBatch. %s", e.getMessage()), e);
    }
  }

  protected ScanStats getScanStats(PlannerSettings settings, EasyGroupScan scan) {
    long data = 0;
    for (CompleteFileWork work : scan.getWorkIterable()) {
      data += work.getTotalBytes();
    }

    final long estRowCount = data / 1024;
    return new ScanStats(GroupScanProperty.NO_EXACT_ROW_COUNT, estRowCount, 1, data);
  }

  @Override
  public AbstractWriter getWriter(PhysicalOperator child, String location, List<String> partitionColumns) {
    return new EasyWriter(child, location, partitionColumns, this);
  }

  @Override
  public AbstractGroupScan getGroupScan(String userName, FileSelection selection, List<SchemaPath> columns)
      throws IOException {
    return new EasyGroupScan(userName, selection, this, columns, selection.selectionRoot, null);
  }

  @Override
  public AbstractGroupScan getGroupScan(String userName, FileSelection selection,
      List<SchemaPath> columns, MetadataProviderManager metadataProviderManager) throws IOException {
    return new EasyGroupScan(userName, selection, this, columns, selection.selectionRoot, metadataProviderManager);
  }

  @Override
  public T getConfig() { return formatConfig; }

  @Override
  public StoragePluginConfig getStorageConfig() { return storageConfig; }

  @Override
  public boolean supportsRead() { return easyConfig.readable; }

  @Override
  public boolean supportsWrite() { return easyConfig.writable; }

  @Override
  public boolean supportsAutoPartitioning() { return easyConfig.supportsAutoPartitioning; }

  @Override
  public FormatMatcher getMatcher() { return easyConfig.matcher; }

  @Override
  public Set<StoragePluginOptimizerRule> getOptimizerRules() {
    return ImmutableSet.of();
  }

  public int getReaderOperatorType() { return easyConfig.readerOperatorType; }
  public int getWriterOperatorType() { return easyConfig.writerOperatorType; }

  @Override
  public boolean supportsStatistics() { return easyConfig.supportsStatistics; }

  @Override
  public TableStatistics readStatistics(FileSystem fs, Path statsTablePath) throws IOException {
    throw new UnsupportedOperationException("unimplemented");
  }

  @Override
  public void writeStatistics(TableStatistics statistics, FileSystem fs,
      Path statsTablePath) throws IOException {
    throw new UnsupportedOperationException("unimplemented");
  }
}
