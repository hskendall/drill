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
package org.apache.drill.exec.record;

import java.util.List;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;

/**
 * Metadata description of the schema of a row or a map.
 * In Drill, both rows and maps are
 * tuples: both are an ordered collection of values, defined by a
 * schema. Each tuple has a schema that defines the column ordering
 * for indexed access. Each tuple also provides methods to get column
 * accessors by name or index.
 * <p>
 * Models the physical schema of a row set showing the logical hierarchy of fields
 * with map fields as first-class fields. Map members appear as children
 * under the map, much as they appear in the physical value-vector
 * implementation.
 * <ul>
 * <li>Provides fast lookup by name or index.</li>
 * <li>Provides a nested schema, in this same form, for maps.</li>
 * </ul>
 * This form is useful when performing semantic analysis and when
 * working with vectors.
 * <p>
 * In the future, this structure will also gather metadata useful
 * for vector processing such as expected widths and so on.
 */

public interface TupleMetadata extends Iterable<TupleMetadata.ColumnMetadata> {

  public enum StructureType {
    PRIMITIVE, LIST, TUPLE
  }

  /**
   * Metadata description of a column including names, types and structure
   * information.
   */

  public interface ColumnMetadata {
    public static final int DEFAULT_ARRAY_SIZE = 10;

    StructureType structureType();
    TupleMetadata mapSchema();
    MaterializedField schema();
    String name();
    MajorType majorType();
    MinorType type();
    DataMode mode();
    boolean isNullable();
    boolean isArray();
    boolean isVariableWidth();
    boolean isMap();
    boolean isList();

    /**
     * Report whether one column is equivalent to another. Columns are equivalent
     * if they have the same name, type and structure (ignoring internal structure
     * such as offset vectors.)
     */

    boolean isEquivalent(ColumnMetadata other);

    /**
     * For variable-width columns, specify the expected column width to be used
     * when allocating a new vector. Does nothing for fixed-width columns.
     *
     * @param width the expected column width
     */

    void setExpectedWidth(int width);

    /**
     * Get the expected width for a column. This is the actual width for fixed-
     * width columns, the specified width (defaulting to 50) for variable-width
     * columns.
     * @return the expected column width of the each data value. Does not include
     * "overhead" space such as for the null-value vector or offset vector
     */

    int expectedWidth();

    /**
     * For an array column, specify the expected average array cardinality.
     * Ignored for non-array columns. Used when allocating new vectors.
     *
     * @param childCount the expected average array cardinality. Defaults to
     * 1 for non-array columns, 10 for array columns
     */

    void setExpectedElementCount(int childCount);

    /**
     * Returns the expected array cardinality for array columns, or 1 for
     * non-array columns.
     *
     * @return the expected value cardinality per value (per-row for top-level
     * columns, per array element for arrays within lists)
     */

    int expectedElementCount();

    /**
     * Create an empty version of this column. If the column is a scalar,
     * produces a simple copy. If a map, produces a clone without child
     * columns.
     *
     * @return empty clone of this column
     */

    ColumnMetadata cloneEmpty();
  }

  /**
   * Add a new column to the schema.
   *
   * @param columnSchema
   * @return the index of the new column
   */
  ColumnMetadata add(MaterializedField field);
  int addColumn(ColumnMetadata column);

  int size();
  boolean isEmpty();
  int index(String name);
  ColumnMetadata metadata(int index);
  ColumnMetadata metadata(String name);
  MaterializedField column(int index);
  MaterializedField column(String name);
  boolean isEquivalent(TupleMetadata other);
  ColumnMetadata parent();

  /**
   * Return the schema as a list of <tt>MaterializedField</tt> objects
   * which can be used to create other schemas. Not valid for a
   * flattened schema.
   *
   * @return a list of the top-level fields. Maps contain their child
   * fields
   */

  List<MaterializedField> toFieldList();

  /**
   * Full name of the column. Note: this name cannot be used to look up
   * the column because of ambiguity. The name "a.b.c" may mean a single
   * column with that name, or may mean maps "a", and "b" with column "c",
   * etc.
   *
   * @return full, dotted, column name
   */

  String fullName(ColumnMetadata column);
  String fullName(int index);
}
