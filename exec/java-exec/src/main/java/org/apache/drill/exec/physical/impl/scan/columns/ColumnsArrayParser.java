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

import java.util.ArrayList;
import java.util.List;

import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.physical.impl.scan.columns.ColumnsArrayManager.UnresolvedColumnsArrayColumn;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.ColumnProjection;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.ScanProjectionParser;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.UnresolvedColumn;
import org.apache.drill.exec.vector.ValueVector;

/**
 * Parses the `columns` array. Doing so is surprisingly complex.
 * <ul>
 * <li>Depending on what is known about the input file, the `columns`
 * array may be required or optional.</li>
 * <li>If the columns array is required, then the wildcard (`*`)
 * expands to `columns`.</li>
 * <li>If the columns array appears, then no other table columns
 * can appear.</li>
 * <li>If the columns array appears, then the wildcard cannot also
 * appear, unless that wildcard expanded to be `columns` as
 * described above.</li>
 * <li>The query can select specific elements such as `columns`[2].
 * In this case, only array elements can appear, not the unindexed
 * `columns` column.</li>
 * </ul>
 */

public class ColumnsArrayParser implements ScanProjectionParser {

  // Config

  private final boolean requireColumnsArray;

  // Internals

  private ScanLevelProjection builder;
  private List<Integer> columnsIndexes;
  private int maxIndex;

  // Output

  private UnresolvedColumnsArrayColumn columnsArrayCol;

  public ColumnsArrayParser(boolean requireColumnsArray) {
    this.requireColumnsArray = requireColumnsArray;
  }

  @Override
  public void bind(ScanLevelProjection builder) {
    this.builder = builder;
  }

  @Override
  public boolean parse(SchemaPath inCol) {
    if (requireColumnsArray && inCol.isWildcard()) {
      expandWildcard();
      return true;
    }
    if (! inCol.nameEquals(ColumnsArrayManager.COLUMNS_COL)) {
      return false;
    }

    // Special `columns` array column.

    mapColumnsArrayColumn(inCol);
    return true;
  }

  /**
   * Query contained SELECT *, and we know that the reader supports only
   * the `columns` array; go ahead and expand the wildcard to the only
   * possible column.
   */

  private void expandWildcard() {
    if (columnsArrayCol != null) {
      throw new IllegalArgumentException("Cannot select columns[] and `*` together");
    }
    addColumnsArrayColumn(SchemaPath.getSimplePath(ColumnsArrayManager.COLUMNS_COL));
  }

  private void mapColumnsArrayColumn(SchemaPath inCol) {

    if (inCol.isArray()) {
      mapColumnsArrayElement(inCol);
      return;
    }

    // Query contains a reference to the "columns" generic
    // columns array. The query can refer to this column only once
    // (in non-indexed form.)

    if (columnsIndexes != null) {
      throw new IllegalArgumentException("Cannot refer to both columns and columns[i]");
    }
    if (columnsArrayCol != null) {
      throw new IllegalArgumentException("Duplicate columns[] column");
    }
    addColumnsArrayColumn(inCol);
  }

  private void addColumnsArrayColumn(SchemaPath inCol) {
    columnsArrayCol = new UnresolvedColumnsArrayColumn(inCol);
    builder.addTableColumn(columnsArrayCol);
  }

  private void mapColumnsArrayElement(SchemaPath inCol) {

    // Add the "columns" column, if not already present.
    // The project list past this point will contain just the
    // "columns" entry rather than the series of
    // columns[1], columns[2], etc. items that appear in the original
    // project list.

    if (columnsArrayCol == null) {
      addColumnsArrayColumn(SchemaPath.getSimplePath(inCol.rootName()));

      // Check if "columns" already appeared without an index.

    } else if (columnsIndexes == null) {
      throw new IllegalArgumentException("Cannot refer to both columns and columns[i]");
    }
    if (columnsIndexes == null) {
      columnsIndexes = new ArrayList<>();
    }
    int index = inCol.getRootSegment().getChild().getArraySegment().getIndex();
    if (index < 0  ||  index > ValueVector.MAX_ROW_COUNT) {
      throw new IllegalArgumentException("columns[" + index + "] out of bounds");
    }
    columnsIndexes.add(index);
    maxIndex = Math.max(maxIndex, index);
  }

  @Override
  public void validate() {
    if (builder.hasWildcard() && columnsArrayCol != null) {
      throw new IllegalArgumentException("Cannot select columns[] and `*` together");
    }
  }

  @Override
  public void validateColumn(ColumnProjection col) {
    if (col.nodeType() == UnresolvedColumn.UNRESOLVED) {
      if (columnsArrayCol != null) {
        throw new IllegalArgumentException("Cannot select columns[] and other table columns: " + col.name());
      }
      if (requireColumnsArray) {
        throw new IllegalArgumentException("Only `columns` column is allowed. Found: " + col.name());
      }
    }
  }

  @Override
  public void build() {
    if (columnsIndexes == null) {
      return;
    }
    boolean indexes[] = new boolean[maxIndex + 1];
    for (Integer index : columnsIndexes) {
      indexes[index] = true;
    }
    columnsArrayCol.setIndexes(indexes);
  }

  public UnresolvedColumnsArrayColumn columnsArrayCol() { return columnsArrayCol; }
}