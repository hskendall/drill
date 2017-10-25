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
package org.apache.drill.exec.physical.impl.scan.columns;

import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.exec.physical.impl.scan.project.RowBatchMerger.Projection;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.ColumnProjection;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.ScanProjectionParser;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.UnresolvedColumn;
import org.apache.drill.exec.physical.impl.scan.project.ScanSchemaOrchestrator;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.ResolvedTableColumn;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.SchemaProjectionResolver;
import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.TupleMetadata;

/**
 * Handles the special case in which the entire row is returned as a
 * "columns" array. The SELECT list can include:
 * <ul>
 * <li><tt>`columns`</tt>: Use the columns array.</li>
 * <li>Wildcard: Equivalent to <tt>columns</tt>.</li>
 * <li>One or more references to specific array members:
 * <tt>columns[2], columns[4], ...</tt>.</li>
 * </ul>
 * In the first two forms, all columns are loaded into the
 * <tt>columns</tt> array. In the third form, only the columns
 * listed are loaded, the other slots will exist, but will be empty.
 * <p>
 * The <tt>columns</tt> mechanism works in conjunction with a reader that
 * is aware of this model. For example, the text reader can be configured
 * to use the <tt>columns</tt> mechanism, or to pick out columns by name.
 * The reader and this mechanism must be coordinated: configure this
 * mechanism only when the reader itself is configured to use model. That
 * configuration is done outside of this mechanism; it is typically done
 * when setting up the scan operator.
 * <p>
 * The output of this mechanism is a specialized projected column that
 * identifies itself as the <tt>columns</tt> column, and optionally holds
 * the list of selected elements.
 *
 * <h4>Configuration</h4>
 *
 * The mechanism handles two use cases:
 * <ul>
 * <li>The reader uses normal named columns. In this case the scan operator
 * should not configure this mechanism; that will allow a column called
 * <tt>columns</tt> to work like any other column: no special handling.</li>
 * <li>The reader uses a single <tt>columns</tt> array as described below.</li>
 * </ul>
 *
 * The scan operator configures this mechanism and adds it to the
 * {@link ScanSchemaOrchestrator}. The associated parser handles the scan-level
 * projection resolution.
 * <p>
 * The reader produces a schema with a single column, <tt>columns</tt>, of the
 * agreed-upon type (as configured here.)
 * <p>
 * Although the {@link ResultSetLoader} automatically handles column-level
 * projection; it does not handle array-level projection. (Perhaps we might want
 * to add that later.) Instead, the reader is given a pointer to this mechanism
 * from which it can retrieve the desired set of array items, and writes only those
 * items to the array column via the usual vector writer mechanism.
 */

public class ColumnsArrayManager implements SchemaProjectionResolver {

  public static class UnresolvedColumnsArrayColumn extends UnresolvedColumn {

    public static final int ID = 20;

    protected boolean selectedIndexes[];

    public UnresolvedColumnsArrayColumn(SchemaPath inCol) {
      super(inCol, ID);
    }

    public void setIndexes(boolean indexes[]) {
      selectedIndexes = indexes;
    }

    public boolean[] selectedIndexes() { return selectedIndexes; }

    @Override
    public boolean isTableProjection() { return true; }
  }

  public static class ResolvedColumnsArrayColumn extends ResolvedTableColumn {

    public static final int ID = 21;

    private final boolean selectedIndexes[];

    public ResolvedColumnsArrayColumn(UnresolvedColumnsArrayColumn unresolved,
        MaterializedField schema,
        Projection projection) {
      super(unresolved.name(), schema, projection);
      selectedIndexes = unresolved.selectedIndexes;
    }

    @Override
    public int nodeType() { return ID; }

    public boolean[] selectedIndexes() { return selectedIndexes; }
  }

  // Internal

  private final ColumnsArrayParser parser;

  private SchemaLevelProjection schemaProj;

  public static final String COLUMNS_COL = "columns";

  public ColumnsArrayManager() {
    parser = new ColumnsArrayParser();
  }

  public ScanProjectionParser projectionParser() { return parser; }

  public SchemaProjectionResolver resolver() { return this; }

  @Override
  public void bind(SchemaLevelProjection schemaProj) {

    this.schemaProj = schemaProj;

    // Verify that the reader fulfilled its obligation to return just
    // one column of the proper name and type.

    TupleMetadata tableSchema = schemaProj.tableSchema();
    if (tableSchema.size() != 1) {
      throw new IllegalStateException("Table schema must have exactly one column.");
    }
  }

  @Override
  public boolean resolveColumn(ColumnProjection col) {
    if (col.nodeType() != UnresolvedColumnsArrayColumn.ID) {
      return false;
    }

    TupleMetadata tableSchema = schemaProj.tableSchema();
    int tabColIndex = tableSchema.index(COLUMNS_COL);
    if (tabColIndex == -1) {
      throw new IllegalStateException("Table schema must include only one column named `" + COLUMNS_COL + "`");
    }
    MaterializedField tableCol = tableSchema.column(tabColIndex);
    if (tableCol.getType().getMode() != DataMode.REPEATED) {
      throw new IllegalStateException("Table schema column `" + COLUMNS_COL +
          "` is of mode " + tableCol.getType().getMode() +
          " but expected " + DataMode.REPEATED);
    }

    // Turn the columns array column into a routine table column.

    schemaProj.addOutputColumn(new ResolvedColumnsArrayColumn((UnresolvedColumnsArrayColumn) col,
        tableCol,
        schemaProj.tableProjection(tabColIndex)));
    return true;
  }

  public boolean[] elementProjection() {
    UnresolvedColumnsArrayColumn columnsArrayCol = parser.columnsArrayCol();
    return columnsArrayCol == null ? null : columnsArrayCol.selectedIndexes();
  }
}