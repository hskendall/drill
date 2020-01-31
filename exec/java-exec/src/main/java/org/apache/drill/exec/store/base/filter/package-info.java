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
 * Provides a standard, reusable framework for implementing filter push-down
 * for storage plugins. Handles the work of parsing a Drill physical plan
 * (using Calcite rules) to extract candidate predicates, converting those
 * to a normalized form, and calling a listener to check if the predicates
 * are eligible for push-down, then to implement the push-down.
 * <p>
 * Some plugins may wish to remove the pushed conditions. That way, Drill
 * does not do work that the plugin has already done. In the ideal case,
 * Drill can omit a filter operator entirely.
 * <p>
 * In other cases, the plugin might only make a "best effort", and wishes
 * to allow Drill to still apply the filter conditions as a final check.
 * <p>
 * The listener can implement both forms (or other variations) by
 * choosing which predicates to leave in the filter.
 *
 * <h4>Serialization</h4>
 *
 * A plugin can simply serialize the {@link RelOp} conditions as part
 * of the sub scan, allowing the run-time scan operator to implement the
 * push down. (This works well for sources such as JDBC or REST.) In
 * other cases (such as Parquet), the terms can be used at plan time
 * (to prune partition directories). The <code>RelOp</code> class
 * is designed for serialization when the plugin chooses to include
 * it in the sub scan.
 *
 * @See {@link DummyStoragePlugin} for an example of how to use this
 * mechanism. This plugin is the "test mule" for this package.
 */
package org.apache.drill.exec.store.base.filter;
