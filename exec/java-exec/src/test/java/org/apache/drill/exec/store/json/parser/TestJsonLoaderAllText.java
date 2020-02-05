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
package org.apache.drill.exec.store.json.parser;

import static org.apache.drill.test.rowSet.RowSetUtilities.strArray;

import org.apache.drill.categories.RowSetTests;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.rowSet.RowSet;
import org.apache.drill.exec.physical.rowSet.RowSetBuilder;
import org.apache.drill.exec.record.metadata.SchemaBuilder;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.RowSetUtilities;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(RowSetTests.class)
public class TestJsonLoaderAllText extends BaseTestJsonLoader {

  private JsonFixture jsonFixture(String json) {
    JsonFixture fixture = new JsonFixture();
    fixture.options.allTextMode = true;
    fixture.open(json);
    return fixture;
  }

  @Test
  @Ignore("Turns out all text can't handle structures")
  public void testRootTupleAllTextComplex() {
    final String json =
      "{id: 1, name: \"Fred\", balance: 100.0, extra: [\"a\",   \"\\\"b,\\\", said I\" ]}\n" +
      "{id: 2, name: \"Barney\", extra: {a:  10 , b:20}}\n" +
      "{id: 3, name: \"Wilma\", balance: 500.00, extra: null}";
    final JsonFixture tester = jsonFixture(json);
    final RowSet results = tester.read();
    final TupleMetadata expectedSchema = new SchemaBuilder()
        .addNullable("id", MinorType.VARCHAR)
        .addNullable("name", MinorType.VARCHAR)
        .addNullable("balance", MinorType.VARCHAR)
        .addNullable("extra", MinorType.VARCHAR)
        .buildSchema();
    final RowSet expected = new RowSetBuilder(fixture.allocator(), expectedSchema)
        .addRow("1", "Fred", "100.0", "[\"a\", \"\\\"b,\\\", said I\"]")
        .addRow("2", "Barney", null, "{\"a\": 10, \"b\": 20}")
        .addRow("3", "Wilma", "500.00", null)
        .build();
    new RowSetComparison(expected)
      .verifyAndClearAll(results);
    tester.close();
  }

  @Test
  public void testRootTupleAllText() {
    final String json =
      "{id: 1, name: \"Fred\", balance: 100.0, extra: true}\n" +
      "{id: 2, name: \"Barney\", extra: 10}\n" +
      "{id: 3, name: \"Wilma\", balance: 500.00, extra: null}\n" +
      "{id: 4, name: \"Betty\", balance: 12.00, extra: \"Hello\"}";
    final JsonFixture tester = jsonFixture(json);
    final RowSet results = tester.read();
    final TupleMetadata expectedSchema = new SchemaBuilder()
        .addNullable("id", MinorType.VARCHAR)
        .addNullable("name", MinorType.VARCHAR)
        .addNullable("balance", MinorType.VARCHAR)
        .addNullable("extra", MinorType.VARCHAR)
        .buildSchema();
    final RowSet expected = new RowSetBuilder(fixture.allocator(), expectedSchema)
        .addRow("1", "Fred", "100.0", "true")
        .addRow("2", "Barney", null, "10")
        .addRow("3", "Wilma", "500.00", null)
        .addRow("4", "Betty", "12.00", "Hello")
        .build();
    new RowSetComparison(expected)
      .verifyAndClearAll(results);
    tester.close();
  }

  @Test
  public void testAllTextArray() {
    final String json =
        "{a: [\"foo\", true, false, 10, null, 20.0] }";
    final JsonFixture tester = jsonFixture(json);
    final RowSet results = tester.read();
    final TupleMetadata expectedSchema = new SchemaBuilder()
        .addArray("a", MinorType.VARCHAR)
        .buildSchema();
    final RowSet expected = new RowSetBuilder(fixture.allocator(), expectedSchema)
        .addSingleCol(strArray("foo", "true", "false", "10", "", "20.0"))
        .build();
    RowSetUtilities.verify(expected, results);
    tester.close();
  }
}
