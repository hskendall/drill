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
 * Handles the details of the result set loader implementation.
 * <p>
 * The primary purpose of this loader, and the most complex to understand and
 * maintain, is overflow handling.
 *
 * <h4>Detailed Use Cases</h4>
 *
 * Let's examine it by considering a number of
 * use cases.
 * <table style="border: 1px solid; border-collapse: collapse;">
 * <tr><th>Row</th><th>a</th><th>b</th><th>c</th><th>d</th><th>e</th><th>f</th><th>g</th><th>h</th></tr>
 * <tr><td>n-2</td><td>X</td><td>X</td><td>X</td><td>X</td><td>X</td><td>X</td><td>-</td><td>-</td></tr>
 * <tr><td>n-1</td><td>X</td><td>X</td><td>X</td><td>X</td><td> </td><td> </td><td>-</td><td>-</td></tr>
 * <tr><td>n  </td><td>X</td><td>!</td><td>O</td><td> </td><td>O</td><td> </td><td>O</td><td> </td></tr>
 * </table>
 * Here:
 * <ul>
 * <li>n-2, n-1, and n are rows. n is the overflow row.</li>
 * <li>X indicates a value was written before overflow.</li>
 * <li>Blank indicates no value was written in that row.</li>
 * <li>! indicates the value that triggered overflow.</li>
 * <li>- indicates a column that did not exist prior to overflow.</li>
 * </ul>
 * Column a is written before overflow occurs, b causes overflow, and all other
 * columns either are not written, or written after overflow.
 * <p>
 * The scenarios, identified by column names above, are:
 * <dl>
 * <dt>a</dt>
 * <dd>a contains values for all three rows.
 * <ul>
 * <li>Two values were written in the "main" batch, while a third was written to
 * what becomes the overflow row.</li>
 * <li>When overflow occurs, the last write position is at n. It must be moved
 * back to n-1.</li>
 * <li>Since data was written to the overflow row, it is copied to the look-
 * ahead batch.</li>
 * <li>The last write position in the lookahead batch is 0 (since data was
 * copied into the 0th row.</li>
 * <li>When harvesting, no empty-filling is needed.</li>
 * <li>When starting the next batch, the last write position must be set to 0 to
 * reflect the presence of the value for row n.</li>
 * </ul>
 * </dd>
 * <dt>b</dt>
 * <dd>b contains values for all three rows. The value for row n triggers
 * overflow.
 * <ul>
 * <li>The last write position is at n-1, which is kept for the "main"
 * vector.</li>
 * <li>A new overflow vector is created and starts empty, with the last write
 * position at -1.</li>
 * <li>Once created, b is immediately written to the overflow vector, advancing
 * the last write position to 0.</li>
 * <li>Harvesting, and starting the next for column b works the same as column
 * a.</li>
 * </ul>
 * </dd>
 * <dt>c</dt>
 * <dd>Column c has values for all rows.
 * <ul>
 * <li>The value for row n is written after overflow.</li>
 * <li>At overflow, the last write position is at n-1.</li>
 * <li>At overflow, a new lookahead vector is created with the last write
 * position at -1.</li>
 * <li>The value of c is written to the lookahead vector, advancing the last
 * write position to -1.</li>
 * <li>Harvesting, and starting the next for column c works the same as column
 * a.</li>
 * </ul>
 * </dd>
 * <dt>d</</dt>
 * <dd>Column d writes values to the last two rows before overflow, but not to
 * the overflow row.
 * <ul>
 * <li>The last write position for the main batch is at n-1.</li>
 * <li>The last write position in the lookahead batch remains at -1.</li>
 * <li>Harvesting for column d requires filling an empty value for row n-1.</li>
 * <li>When starting the next batch, the last write position must be set to -1,
 * indicating no data yet written.</li>
 * </ul>
 * </dd>
 * <dt>f</dt>
 * <dd>Column f has no data in the last position of the main batch, and no data
 * in the overflow row.
 * <ul>
 * <li>The last write position is at n-2.</li>
 * <li>An empty value must be written into position n-1 during harvest.</li>
 * <li>On start of the next batch, the last write position starts at -1.</li>
 * </ul>
 * </dd>
 * <dt>g</dt>
 * <dd>Column g is added after overflow, and has a value written to the overflow
 * row.
 * <ul>
 * <li>On harvest, column g is simply skipped.</li>
 * <li>On start of the next row, the last write position can be left unchanged
 * since no "exchange" was done.</li>
 * </ul>
 * </dd>
 * <dt>h</dt>
 * <dd>Column h is added after overflow, but does not have data written to it
 * during the overflow row. Similar to column g, but the last write position
 * starts at -1 for the next batch.</dd>
 * </dl>
 *
 * <h4>General Rules</h4>
 *
 * The above can be summarized into a smaller set of rules:
 * <p>
 * At the time of overflow on row n:
 * <ul>
 * <li>Create or clear the lookahead vector.</li>
 * <li>Copy (last write position - n) values from row n in the old vector to 0
 * in the new one. If (last write position - n) is negative, copy nothing.</li>
 * <li>Save the last write position from the old vector, clamped at n.</li>
 * <li>Set the last write position of the overflow vector to (original last
 * write position - n), clamped at -1.</li>
 * <li>Swap buffers from the main vectors and the overflow vectors. This sets
 * aside the main values, and allows writing to continue using the overflow
 * buffers.</li>
 * </ul>
 * <p>
 * As the overflow write proceeds:
 * <ul>
 * <li>For existing columns, write as normal. The last write position moves from
 * -1 to 0.</li>
 * <li>Columns not written leave the last write position at -1.</li>
 * <li>If a new column appears, set its last write position to -1. If it is then
 * written, proceed as in the first point above.</li>
 * </ul>
 * <p>
 * At harvest time:
 * <ul>
 * <li>For every writer, save the last write position.</li>
 * <li>Swap the overflow and main buffers to put the main batch back into the
 * main vectors.</li>
 * <li>Reset the last write position for all writers to the values saved at
 * overflow time above.</li>
 * <li>Finish the batch for the main vectors as normal. No special handling
 * needed.</li>
 * </ul>
 * <p>
 * When starting the next batch:
 * <ul>
 * <li>Swap buffers again, putting the overflow row back into the main vectors.
 * (At this point, the harvested vectors should all have zero buffers.)</li>
 * <li>Restore the last write position saved during harvest.</li>
 * </ul>
 * <h4>Constraints</h4>
 * A number of constraints are worth pointing out:
 * <ul>
 * <li>Writers are bound to vectors, so we can't easily swap vectors during
 * overflow.</li>
 * <li>The project operator to which this operator feeds data also binds to
 * vectors, so the same set of vectors must be presented on every batch.</li>
 * <li>The client binds to writers, so we cannot swap writers between main and
 * overflow batches.</li>
 * <li>Therefore, the unit of swapping is the buffer that backs the vectors.
 * </li>
 * <li>Swapping is not copying; it is only exchanging pointers.</li>
 * <li>The only copying in this entire process occurs when moving previously-
 * written values in the overflow row to the new vector at the time of
 * overflow.</li>
 * </ul>
 *
 * <h4>Arrays</h4>
 *
 * The above covers the case of scalar, top-level columns. The extension to
 * scalar maps is straightforward: at run time, the members of maps are just
 * simple scalar vectors that reside in a map name space, but the structure
 * of map fields is the same as for top-level fields. (Think of map fields
 * as being "flattened" into the top-level tuple.)
 * <p>
 * Arrays are a different matter: each row can have many values associated
 * with it. Consider an array of scalars. We have:
 * <pre><code>
 *    Row 0   Row 1     Row 2
 *    0 1 2   3 4 5     6 7 8
 * [ [a b c] [d e f] | [g h i] ]
 * </code></pre>
 * Here, the letters indicate values. The brackets show the overall vector
 * (outer brackets) and individual rows (inner brackets). The vertical line
 * shows where overflow occurred. The same rules as discussed earier still
 * apply, but we must consider both the row indexes and the array indexes.
 * <ul>
 * <li>Overflow occurs at the row level. Here row 2 overflowed and must
 * be moved to the look-ahead vector.</li>
 * <li>Value movement occurs at the value level. Here, values 6, 7 and 8
 * must be move to the look-ahead vector.</li>
 * </ul>
 * The result, after overflow, is:
 * <pre><code>
 *    Row 0   Row 1       Row 0
 *    0 1 2   3 4 5       0 1 2
 * [ [a b c] [d e f] ] [ [g h i] ]
 * </code></pre>
 * Further, we must consider lists: a column may consist of a list of
 * arrays. Or, a column may consist of an array of maps, one of which is
 * a list of arrays. So, the above reasoning must apply recursively down
 * the value tree.
 * <p>
 * As it turns out, there is a simple recursive algorithm, which is a
 * simple extension of the reasoning for the top-level scalar case, that can
 * handle arrays:
 * <ul>
 * <li>Start with the row index of the overflow row.</li>
 * <li>If column c, say, is an array, obtain the index of the first value for
 * the overflow row.</li>
 * <li>If c is a list, or a repeated map, then repeat the above, for each
 * member of c (a single column for a list, a set of columns for a map), but
 * replace the row index with the index of the first element.</li>
 * </ul>
 * The result will be a walk of the value tree in which the overflow index
 * starts as an index relative to the result set (a row index), and is
 * recursively replaced with an array offset for each level of the array.
 *
 * <h4>The Need for a Uniform Structure</h4>
 *
 * Drill has vastly different implementations and interfaces for:
 * <ul>
 * <li>Result sets (as a {@link VectorContainer}),</li>
 * <li>Arrays (as a generated repeated vector),</li>
 * <li>Lists (as a {@link ListVector}),</li>
 * <li>Repeated lists (as a {@link RepeatedList vector}, and</li>
 * <li>Repeated maps ({@link RepeatedMapVector}.</li>
 * </ul>
 * If we were to work directly with the above abstractions the code would be
 * vastly complex. Instead, we abstract out the common structure into the
 * {@link TupleMode} abstraction. In particular, we use the
 * single tuple model which works with a single batch. This model provides a
 * simple, uniform interface to work with columns and tuples (rows, maps),
 * and a simple way to work with arrays. This interface reduces the above
 * array algorithm to a simple set of recursive method calls.
 */

package org.apache.drill.exec.physical.rowSet.impl;