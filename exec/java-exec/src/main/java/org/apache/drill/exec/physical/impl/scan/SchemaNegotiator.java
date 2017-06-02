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

import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.hadoop.fs.Path;

/**
 * Negotiate the select and table schemas with the scan operator. Depending
 * on the design of the storage plugin, the selection list may be something that
 * the scan operator understands, or that the record reader understands. If the
 * storage plugin; the <tt>addSelectColumn()</tt> methods are not needed. But,
 * if the record reader defines the select list, call the various
 * <tt>addSelectColumn()</tt> methods to specify the select. All methods are
 * equivalent, though processing can be saved if the reader knows the column
 * type.
 * <p>
 * All readers must announce the table schema. This can be done at open time
 * for an "early schema" reader. But, if the schema is not known at open
 * time, then the reader is a "late schema" reader and schema will be
 * discovered, and adjusted, on each batch.
 * <p>
 * Regardless of the schema type, the result of building the schema is a
 * result set loader used to prepare batches for use in the query. If the
 * select list contains a subset of columns from the table, then the result
 * set loader will return null when the reader asks for the column loader for
 * that column. The null value tells the reader to skip that column. The reader
 * can use that information to avoid reading the data, if possible, for
 * efficiency.
 */

public interface SchemaNegotiator {

  /**
   * Indicates the way that the table handles schema.
   */
  enum TableSchemaType {
    /**
     * The table provides the schema via this negotiator, by calling a
     * {@link SchemaNegotiator#addTableColumn} method.
     */
    EARLY,
    /**
     * The reader learns of the table schema only by reading the data
     * for the table. (Example: JSON.) No schema is provided to the
     * negotiator.
     */
    LATE
  }

  /**
   * For readers that specify the SELECT list, and that have information
   * about the columns, specifies the column meaning. This information is
   * optional and not yet available from the Drill planner.
   */
  enum ColumnType {
    /**
     * The column can be of any type; the scan operator will figure out
     * the meaning. (Default)
     */
    ANY,
    /**
     * The column names a table column.
     */
    TABLE,
    /**
     * The column is some form of meta-data (as opposed to a
     * table column.)
     */
    META_ANY,
    /**
     * The columns is implicit: fqn, filename, filepath or
     * suffix.
     */
    META_IMPLICIT,
    /**
     * The column is a partition identifier: div0, div1, etc.
     */
    META_PARTITION };

  void addSelectColumn(String name);
  void addSelectColumn(SchemaPath path);
  void addSelectColumn(SchemaPath path, SchemaNegotiator.ColumnType type);

  /**
   * Specify the type of table schema. Required only in the obscure
   * case of an early-schema table with an empty schema, else inferred.
   * (Set to {@link TableSchemaType#EARLY} if no columns provided, or
   * to {@link TableSchemaType#LATE if at least one column is provided.)
   * @param type the table schema type
   */

  void setTableSchemaType(SchemaNegotiator.TableSchemaType type);
  void addTableColumn(String name, MajorType type);
  void addTableColumn(MaterializedField schema);

  /**
   * Specify the file path, if any, for the file to be read.
   * Used to populate implicit columns.
   * @param filePath Hadoop file path for the file
   */

  void setFilePath(Path filePath);

  /**
   * Specify the selection root for a directory scan, if any.
   * Used to populate partition columns.
   * @param rootPath Hadoop file path for the directory
   */

  void setSelectionRoot(Path rootPath);

  /**
   * Specify the type to use for projected columns that do not
   * match any data source columns. Defaults to nullable int.
   */

  void setNullType(MajorType type);

  /**
   * Build the schema, plan the required projections and static
   * columns and return a loader used to populate value vectors.
   * If the select list includes a subset of table columns, then
   * the loader will be set up in table schema order, but the unneeded
   * column loaders will be null, meaning that the batch reader should
   * skip setting those columns.
   *
   * @return the loader for the table with columns arranged in table
   * schema order
   */

  ResultSetLoader build();
  // TODO: return a projection map as an array of booleans
}