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
package org.apache.drill.exec.physical.impl.scan.framework;

import java.util.List;

import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.scan.ScanOperatorEvents;
import org.apache.drill.exec.physical.impl.scan.project.ScanSchemaOrchestrator;

/**
 * Provides the row set mutator used to construct record batches.
 * <p>
 * Provides the option to continue a schema from one batch to the next.
 * This can reduce spurious schema changes in formats, such as JSON, with
 * varying fields. It is not, however, a complete solution as the outcome
 * still depends on the order of file scans and the division of files across
 * readers.
 * <p>
 * Provides the option to infer the schema from the first batch. The "quick path"
 * to obtain the schema will read one batch, then use that schema as the returned
 * schema, returning the full batch in the next call to <tt>next()</tt>.
 */

public abstract class AbstractScanFramework<T extends SchemaNegotiator> implements ScanOperatorEvents {

  protected final List<SchemaPath> projection;
  protected MajorType nullType;
  protected int maxBatchRowCount;
  protected int maxBatchByteCount;
  protected OperatorContext context;
  protected ScanSchemaOrchestrator scanProjector;

  public AbstractScanFramework(List<SchemaPath> projection) {
    this.projection = projection;
  }

  /**
   * Specify the type to use for projected columns that do not
   * match any data source columns. Defaults to nullable int.
   */

  public void setNullType(MajorType type) {
    this.nullType = type;
  }

  public void setMaxRowCount(int rowCount) {
    maxBatchRowCount = rowCount;
  }

  public void setMaxBatchByteCount(int byteCount) {
    maxBatchByteCount = byteCount;
  }

  @Override
  public void bind(OperatorContext context) {
    this.context = context;
    scanProjector = new ScanSchemaOrchestrator(context.getAllocator());
    configure();
    assert projection != null  &&  ! projection.isEmpty();
    scanProjector.build(projection);
  }

  public OperatorContext context() { return context; }

  public ScanSchemaOrchestrator projector() {
    return scanProjector;
  }

  protected void configure() {

    // Pass along config options if set.

    if (maxBatchRowCount > 0) {
      scanProjector.setBatchRecordLimit(maxBatchRowCount);
    }
    if (maxBatchByteCount > 0) {
      scanProjector.setBatchByteLimit(maxBatchByteCount);
    }
    if (nullType != null) {
      scanProjector.setNullType(nullType);
    }
  }

  public abstract boolean openReader(ShimBatchReader<T> shim, ManagedReader<T> reader);

  @Override
  public void close() {
    if (scanProjector != null) {
      scanProjector.close();
      scanProjector = null;
    }
  }
}
