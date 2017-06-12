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
package org.apache.drill.exec.physical.impl.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.drill.common.map.CaseInsensitiveMap;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.RequestedTableColumn;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.NullColumn;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.PartitionColumn;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.ProjectedColumn;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.FileMetadata;
import org.apache.drill.exec.record.MaterializedField;

import com.google.common.annotations.VisibleForTesting;

/**
 * Scan projection lifecycle that implements the flow from query-defined
 * projection, to projection with file metadata filled in to a projection
 * with table data filled in.
 * <p>
 * Subclasses handle the two use cases:
 * <ul>
 * <li><i>Discrete</i>: Used for non-wildcard schemas in which the select list
 * itself provides schema continuity.</li>
 * <li><i>Continuous</i>: Used for wildcard (SELECT *) queries in which Drill
 * seeks to keep the projection the same wherever possible across schemas.
 * This is not always possible, but we can handle the simple cases.</li>
 * </ul>
 */

public abstract class ProjectionLifecycle {

  public static class PriorSchema {
    private final List<ScanOutputColumn> generatedSelect;
    private final Map<String,RequestedTableColumn> expectedSchema;
    private final int partitionCount;

    public PriorSchema(Map<String, RequestedTableColumn> expectedSchema,
        List<ScanOutputColumn> generatedSelect, int partitionCount) {
      this.expectedSchema = expectedSchema;
      this.generatedSelect = generatedSelect;
      this.partitionCount = partitionCount;
    }
  }

  public static class GenericSchemaBuilder extends ScanOutputColumn.Visitor {

    protected Map<String, RequestedTableColumn> inferredSchema = CaseInsensitiveMap.newHashMap();
    protected List<ScanOutputColumn> genericCols = new ArrayList<>();
    private int maxPartition = -1;

    public PriorSchema unresolvedSchema() {
      return new PriorSchema(inferredSchema, genericCols, maxPartition + 1);
    }

    @Override
    public void visitProjection(int index, ProjectedColumn col) {
      RequestedTableColumn tableCol = col.unresolve();
      genericCols.add(tableCol);
      inferredSchema.put(tableCol.name(), tableCol);
    }

    @Override
    public void visitNullColumn(int index, NullColumn col) {
      RequestedTableColumn tableCol = col.unresolve();
      genericCols.add(tableCol);
      inferredSchema.put(tableCol.name(), tableCol);
    }

    @Override
    protected void visitPartitionColumn(int index, PartitionColumn col) {
      maxPartition  = Math.max(maxPartition, col.partition());
      visitColumn(index, col);
    }

    @Override
    protected void visitColumn(int index, ScanOutputColumn col) {
      genericCols.add(col.unresolve());
    }
  }

  /**
   * Projection lifecycle for non-wildcard cases. The select list itself
   * provides consistency from one schema to the next. The code that implements
   * vectors handles continuity of null types.
   */

  public static class DiscreteProjectionLifecycle extends ProjectionLifecycle {

    DiscreteProjectionLifecycle(ScanProjectionDefn queryPlan) {
      super(queryPlan);
    }

    @Override
    public void startFile(FileMetadata fileInfo) {
      fileProjDefn = new FileProjectionDefn(scanProjDefn, fileInfo);
      tableProjDefn = null;
      schemaVersion++;
    }

    @Override
    public void startSchema(MaterializedSchema tableSchema) {
      tableProjDefn = fileProjDefn.resolve(tableSchema);
    }
  }

  /**
   * Schema persistence for the wildcard selection (i.e. SELECT *)
   * <p>
   * Constraints:
   * <ul>
   * <li>Adding columns causes a hard schema change.</li>
   * <li>Removing columns is allowed, use type from previous
   * schema, as long as previous mode was nullable or repeated.</li>
   * <li>Changing type or mode causes a hard schema change.</li>
   * <li>Changing column order is fine; use order from previous
   * schema.</li>
   * </ul>
   * This can all be boiled down to a simpler rule:
   * <ul>
   * <li>Schema persistence is possible if the output schema
   * from a prior schema can be reused for the current schema</i>
   * <li>Else, a hard schema change occurs and a new output
   * schema is derived from the new table schema.</li>
   * </ul>
   * The core idea here is to "unresolve" a fully-resolved table schema
   * to produce a new projection list that is the equivalent of using that
   * prior projection list in the SELECT. Then, keep that projection list only
   * if it is compatible with the next table schema, else throw it away and
   * start over from the actual scan projection list.
   * <p>
   * Algorithm:
   * <ul>
   * <li>If partitions are included in the wildcard, and the new
   * file needs more than the current one, create a new schema.</li>
   * <li>Else, treat partitions as select, fill in missing with
   * nulls.</li>
   * <li>From an output schema, construct a new select list
   * specification as though the columns in the current schema were
   * explicitly specified in the SELECT clause.</li>
   * <li>For each new schema column, verify that the column exists
   * in the generated SELECT clause and is of the same type.
   * If not, create a new schema.</li>
   * <li>Use the generated schema to plan a new projection from
   * the new schema to the prior schema.</li>
   * </ul>
   */

  public static class ContinuousProjectionLifecycle extends ProjectionLifecycle {

    private PriorSchema priorSchema;
    private FileMetadata fileInfo;

    private ContinuousProjectionLifecycle(ScanProjectionDefn queryPlan) {
      super(queryPlan);
    }

    @Override
    public void startFile(FileMetadata newFileInfo) {
      this.fileInfo = newFileInfo;
      if (priorSchema != null && isCompatible(newFileInfo)) {
        fileProjDefn = new FileProjectionDefn(scanProjDefn, priorSchema.generatedSelect, fileInfo);
      } else {
        resetFileSchema();
      }
    }

    private void resetFileSchema() {
      priorSchema = null;
      fileProjDefn = new FileProjectionDefn(scanProjDefn, fileInfo);
      schemaVersion++;
    }

    private boolean isCompatible(FileMetadata fileInfo) {

      // Can't smooth over the schema if we need more partition columns
      // than the prior plan.

      if (! scanProjDefn.useLegacyWildcardPartition()) {
        return true;
      }
      return priorSchema.partitionCount >= fileInfo.dirPathLength();
    }

    @Override
    public void startSchema(MaterializedSchema tableSchema) {
      if (priorSchema != null && ! isCompatible(tableSchema)) {
        resetFileSchema();
      }
      tableProjDefn = fileProjDefn.resolve(tableSchema);
      unresolveProjection();
    }

    private boolean isCompatible(MaterializedSchema newSchema) {

      // Can't match if have more table columns than prior columns,
      // new fields appeared in this table.

      // TODO: This does not help with this case (a, b, c) --> (a, b), --> (a, b, c)

      if (priorSchema.expectedSchema.size() < newSchema.size()) {
        return false;
      }
      for (MaterializedField newCol : newSchema) {
        RequestedTableColumn priorCol = priorSchema.expectedSchema.get(newCol.getName());

        // New field in this table; can't preserve schema

        if (priorCol == null) {
          return false;
        }

        // Can't preserve schema if column types differ.

        if (! priorCol.type().equals(newCol.getType())) {
          return false;
        }
      }

      // Can't preserve schema if missing columns are required.

      for (RequestedTableColumn priorCol : priorSchema.expectedSchema.values()) {
        MaterializedField col = newSchema.column(priorCol.name());
        if (col == null  &&  priorCol.type().getMode() == DataMode.REQUIRED) {
          return false;
        }
      }

      // This table schema is a subset of the prior
      // schema.

      return true;
    }

    private void unresolveProjection() {
      GenericSchemaBuilder visitor = new GenericSchemaBuilder();
      visitor.visit(tableProjDefn.output());
      priorSchema = visitor.unresolvedSchema();
    }
  }

  /**
   * Scan projection definition based on static information.
   */

  protected final ScanProjectionDefn scanProjDefn;

  /**
   * Rewritten projection definition with file or partition metadata
   * columns resolved.
   */

  protected FileProjectionDefn fileProjDefn;
  protected TableProjectionDefn tableProjDefn;

  /**
   * Tracks the schema version last seen from the table loader. Used to detect
   * when the reader changes the table loader schema.
   */

  protected int schemaVersion;

  private ProjectionLifecycle(ScanProjectionDefn queryPlan) {
    scanProjDefn = queryPlan;
  }

  public abstract void startFile(FileMetadata fileInfo);
  public abstract void startSchema(MaterializedSchema newSchema);
  public ScanProjectionDefn scanProjection() { return scanProjDefn; }
  public FileProjectionDefn fileProjection() { return fileProjDefn; }
  public TableProjectionDefn tableProjection() { return tableProjDefn; }
  public MaterializedSchema outputSchema() { return tableProjDefn.outputSchema(); }
  public int schemaVersion() { return schemaVersion; }

  @VisibleForTesting
  public static ProjectionLifecycle newDiscreteLifecycle(ScanProjectionDefn.Builder builder) {
    return new DiscreteProjectionLifecycle(builder.build());
  }

  @VisibleForTesting
  public static ProjectionLifecycle newContinuousLifecycle(ScanProjectionDefn.Builder builder) {
    return new ContinuousProjectionLifecycle(builder.build());
  }

  @VisibleForTesting
  public static ProjectionLifecycle newLifecycle(ScanProjectionDefn.Builder builder) {
    return newLifecycle(builder.build());
  }

  public static ProjectionLifecycle newLifecycle(ScanProjectionDefn scanProj) {
    if (scanProj.isProjectAll()) {
      return new ContinuousProjectionLifecycle(scanProj);
    } else {
      return new DiscreteProjectionLifecycle(scanProj);
    }
  }

  public void close() {
    tableProjDefn = null;
    fileProjDefn = null;
  }
}
