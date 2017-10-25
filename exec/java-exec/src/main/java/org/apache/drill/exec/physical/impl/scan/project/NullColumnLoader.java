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
package org.apache.drill.exec.physical.impl.scan.project;

import java.util.List;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.rowSet.ResultVectorCache;
import org.apache.drill.exec.physical.rowSet.RowSetLoader;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.vector.accessor.TupleWriter;

/**
 * Create and populate null columns for the case in which a SELECT statement
 * refers to columns that do not exist in the actual table. Nullable and array
 * types are suitable for null columns. (Drill defines an empty array as the
 * same as a null array: not true, but the best we have at present.) Required
 * types cannot be used as we don't know what value to set into the column
 * values.
 * <p>
 * Seeks to preserve "vector continuity" by reusing vectors when possible.
 * Cases:
 * <ul>
 * <li>A column a was available in a prior reader (or batch), but is no longer
 * available, and is thus null. Reuses the type and vector of the prior reader
 * (or batch) to prevent trivial schema changes.</li>
 * <li>A column has an implied type (specified in the metadata about the
 * column provided by the reader.) That type information is used instead of
 * the defined null column type.</li>
 * <li>A column has no type information. The type becomes the null column type
 * defined by the reader (or nullable int by default.</li>
 * <li>Required columns are not suitable. If any of the above found a required
 * type, convert the type to nullable.</li>
 * <li>The resulting column and type, whatever it turned out to be, is placed
 * into the vector cache so that it can be reused by the next reader or batch,
 * to again preserve vector continuity.</li>
 * </ul>
 * The above rules eliminate "trivia" schema changes, but can still result in
 * "hard" schema changes if a required type is replaced by a nullable type.
 */

public class NullColumnLoader extends StaticColumnLoader {

  public interface NullColumnSpec {
    String name();
    MajorType type();
  }

  public static final MajorType DEFAULT_NULL_TYPE = MajorType.newBuilder()
      .setMinorType(MinorType.INT)
      .setMode(DataMode.OPTIONAL)
      .build();

  private final MajorType nullType;
  private final boolean isArray[];

  public NullColumnLoader(ResultVectorCache vectorCache, List<? extends NullColumnSpec> defns,
      MajorType nullType) {
    super(vectorCache);

    // Use the provided null type, else the standard nullable int.

    if (nullType == null) {
      this.nullType = DEFAULT_NULL_TYPE;
    } else {
      this.nullType = nullType;
    }

    // Populate the loader schema from that provided

    RowSetLoader schema = loader.writer();
    isArray = new boolean[defns.size()];
    for (int i = 0; i < defns.size(); i++) {
      NullColumnSpec defn = defns.get(i);
      MaterializedField colSchema = selectType(defn);
      isArray[i] = colSchema.getDataMode() == DataMode.REPEATED;
      schema.addColumn(colSchema);
    }
  }

  /**
   * Implements the type mapping algorithm; preferring the best fit
   * to preserve the schema, else resorting to changes when needed.
   * @param defn output column definition
   * @return type of the empty column that implements the definition
   */

  private MaterializedField selectType(NullColumnSpec defn) {

    // Prefer the type of any previous occurrence of
    // this column.

    MajorType type = vectorCache.getType(defn.name());

    // Else, use the type defined in the projection, if any.

    if (type == null) {
      type = defn.type();
    }

    // Else, use the specified null type.

    if (type == null) {
      type = nullType;
    }

    // If the schema had the special NULL type, replace it with the
    // null column type.

    if (type.getMinorType() == MinorType.NULL) {
      type = nullType;
    }

    // Map required to optional. Will cause a schema change.

    if (type.getMode() == DataMode.REQUIRED) {
      type = MajorType.newBuilder()
            .setMinorType(type.getMinorType())
            .setMode(DataMode.OPTIONAL)
            .build();
    }
    return MaterializedField.create(defn.name(), type);
  }

  /**
   * Populate nullable values with null, repeated vectors with
   * an empty array (which, in Drill, is equivalent to null.).
   *
   * @param rowCount number of rows to generate. Must match the
   * row count in the batch returned by the reader
   */

  @Override
  protected void loadRow(TupleWriter writer) {
    for (int i = 0; i < isArray.length; i++) {

      // Set the column (of any type) to null if the string value
      // is null.

      if (isArray[i]) {
        // Nothing to do, array empty by default
      } else {
        writer.scalar(i).setNull();
      }
    }
  }
}