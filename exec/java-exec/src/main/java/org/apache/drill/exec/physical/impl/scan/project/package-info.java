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
/**
 * Provides run-time semantic analysis of the projection list for the
 * scan operator. The project list can include table columns and a
 * variety of special columns. Requested columns can exist in the table,
 * or may be "missing" with null values applied. The code here prepares
 * a run-time projection plan based on the actual table schema.
 * <p>
 * The core concept is one of successive refinement of the project
 * list through a set of rewrites:
 * <ul>
 * <li>Scan-level rewrite: convert {@link SchemaPath} entries into
 * internal column nodes, tagging the nodes with the column type:
 * wildcard, unresolved table column, or special columns (such as
 * file metadata.) The scan-level rewrite is done once per scan
 * operator.</li>
 * <li>Reader-level rewrite: convert the internal column nodes into
 * other internal nodes, leaving table column nodes unresolved. The
 * typical use is to fill in metadata columns with information about a
 * specific file.</li>
 * <li>Schema-level rewrite: given the actual schema of a record batch,
 * rewrite the reader-level projection to describe the final projection
 * from incoming data to output container. This step fills in missing
 * columns, expands wildcards, etc.</li>
 * </ul>
 */

package org.apache.drill.exec.physical.impl.scan.project;