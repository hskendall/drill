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
package org.apache.drill.exec.physical.resultSet.project;

import java.util.List;

import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.exec.record.metadata.ColumnMetadata;

/**
 * Represents the set of columns projected for a tuple (row or map.)
 * The projected columns might themselves be columns, so returns a
 * projection set for such columns. Represents the set of requested
 * columns and tuples as expressed in the physical plan.
 * <p>
 * Three variations exist:
 * <ul>
 * <li>Project all ({@link ImpliedTupleRequest#ALL_MEMBERS}): used for a tuple
 * when all columns are projected. Example: the root tuple (the row) in
 * a <tt>SELECT *</tt> query.</li>
 * <li>Project none (also {@link ImpliedTupleRequest#NO_MEMBERS}): used when
 * no columns are projected from a tuple, such as when a map itself is
 * not projected, so none of its member columns are projected.</li>
 * <li>Project some ({@link RequestedTupleImpl}: used in the
 * <tt>SELECT a, c, e</tt> case in which the query identifies which
 * columns to project (implicitly leaving out others, such as b and
 * d in our example.)</li>
 * </ul>
 * <p>
 * The result is that each tuple (row and map) has an associated
 * projection set which the code can query to determine if a newly
 * added column is wanted (and so should have a backing vector) or
 * is unwanted (and can just receive a dummy writer.)
 */

public interface RequestedTuple {

  public enum TupleProjectionType {
    ALL, NONE, SOME
  }

  TupleProjectionType type();
  RequestedColumn get(String colName);
  boolean isProjected(String colName);
  boolean isConsistentWith(ColumnMetadata col);
  boolean isConsistentWith(String name, MajorType type);
  RequestedTuple mapProjection(String colName);
  List<RequestedColumn> projections();
  void buildName(StringBuilder buf);
}
