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
package org.apache.drill.exec.vector.complex.writer;

import static org.apache.drill.test.TestBuilder.listOf;
import static org.apache.drill.test.TestBuilder.mapOf;
import static org.junit.Assert.assertEquals;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.file.Paths;
import org.apache.drill.test.BaseTestQuery;
import org.apache.drill.common.util.DrillFileUtils;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.proto.UserBitShared;
import org.apache.drill.exec.store.easy.json.JSONRecordReader;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

public class TestJsonReader extends BaseTestQuery {

  private static final boolean VERBOSE_DEBUG = false;

  @BeforeClass
  public static void setupTestFiles() {
    dirTestWatcher.copyResourceToRoot(Paths.get("store", "json"));
    dirTestWatcher.copyResourceToRoot(Paths.get("vector","complex", "writer"));
  }

  @Test
  public void dummyDoNotCheckIn() throws Exception {
//    schemaChange();
  }

  // TODO: Requires lists

  @Test
  public void testSplitAndTransferFailure() throws Exception {
    final String testVal = "a string";
    testBuilder()
        .sqlQuery("select flatten(config) as flat from cp.`store/json/null_list.json`")
        .ordered()
        .baselineColumns("flat")
        .baselineValues(listOf())
        .baselineValues(listOf(testVal))
        .go();

    test("select flatten(config) as flat from cp.`store/json/null_list_v2.json`");
    testBuilder()
        .sqlQuery("select flatten(config) as flat from cp.`store/json/null_list_v2.json`")
        .ordered()
        .baselineColumns("flat")
        .baselineValues(mapOf("repeated_varchar", listOf()))
        .baselineValues(mapOf("repeated_varchar", listOf(testVal)))
        .go();

    testBuilder()
        .sqlQuery("select flatten(config) as flat from cp.`store/json/null_list_v3.json`")
        .ordered()
        .baselineColumns("flat")
        .baselineValues(mapOf("repeated_map", listOf(mapOf("repeated_varchar", listOf()))))
        .baselineValues(mapOf("repeated_map", listOf(mapOf("repeated_varchar", listOf(testVal)))))
        .go();
  }

  public void runTestsOnFile(String filename, UserBitShared.QueryType queryType, String[] queries, long[] rowCounts) throws Exception {
    if (VERBOSE_DEBUG) {
      System.out.println("===================");
      System.out.println("source data in json");
      System.out.println("===================");
      System.out.println(Files.toString(DrillFileUtils.getResourceAsFile(filename), Charsets.UTF_8));
    }

    int i = 0;
    for (String query : queries) {
      if (VERBOSE_DEBUG) {
        System.out.println("=====");
        System.out.println("query");
        System.out.println("=====");
        System.out.println(query);
        System.out.println("======");
        System.out.println("result");
        System.out.println("======");
      }
      int rowCount = testRunAndPrint(queryType, query);
      assertEquals(rowCounts[i], rowCount);
      System.out.println();
      i++;
    }
  }

  // TODO

  @Test
  public void testSelectStarWithUnionType() throws Exception {
    try {
      testBuilder()
              .sqlQuery("select * from cp.`jsoninput/union/a.json`")
              .ordered()
              .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
              .baselineColumns("field1", "field2")
              .baselineValues(
                      1L, 1.2
              )
              .baselineValues(
                      listOf(2L), 1.2
              )
              .baselineValues(
                      mapOf("inner1", 3L, "inner2", 4L), listOf(3L, 4.0, "5")
              )
              .baselineValues(
                      mapOf("inner1", 3L,
                              "inner2", listOf(
                                      mapOf(
                                              "innerInner1", 1L,
                                              "innerInner2",
                                              listOf(
                                                      3L,
                                                      "a"
                                              )
                                      )
                              )
                      ),
                      listOf(
                              mapOf("inner3", 7L),
                              4.0,
                              "5",
                              mapOf("inner4", 9L),
                              listOf(
                                      mapOf(
                                              "inner5", 10L,
                                              "inner6", 11L
                                      ),
                                      mapOf(
                                              "inner5", 12L,
                                              "inner7", 13L
                                      )
                              )
                      )
              ).go();
    } finally {
      testNoResult("alter session set `exec.enable_union_type` = false");
    }
  }

  // TODO

  @Test
  public void testSelectFromListWithCase() throws Exception {
    try {
      testBuilder()
              .sqlQuery("select a, typeOf(a) `type` from " +
                "(select case when is_list(field2) then field2[4][1].inner7 end a " +
                "from cp.`jsoninput/union/a.json`) where a is not null")
              .ordered()
              .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
              .baselineColumns("a", "type")
              .baselineValues(13L, "BIGINT")
              .go();
    } finally {
      testNoResult("alter session set `exec.enable_union_type` = false");
    }
  }

  // TODO

  @Test
  public void testTypeCase() throws Exception {
    try {
      testBuilder()
              .sqlQuery("select case when is_bigint(field1) " +
                "then field1 when is_list(field1) then field1[0] " +
                "when is_map(field1) then t.field1.inner1 end f1 from cp.`jsoninput/union/a.json` t")
              .ordered()
              .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
              .baselineColumns("f1")
              .baselineValues(1L)
              .baselineValues(2L)
              .baselineValues(3L)
              .baselineValues(3L)
              .go();
    } finally {
      testNoResult("alter session set `exec.enable_union_type` = false");
    }
  }

  // TODO

  @Test
  public void testSumWithTypeCase() throws Exception {
    alterSession(ExecConstants.ENABLE_UNION_TYPE_KEY, true);
    try {
      testBuilder()
              .sqlQuery("select sum(cast(f1 as bigint)) sum_f1 from " +
                "(select case when is_bigint(field1) then field1 " +
                "when is_list(field1) then field1[0] when is_map(field1) then t.field1.inner1 end f1 " +
                "from cp.`jsoninput/union/a.json` t)")
              .ordered()
              .baselineColumns("sum_f1")
              .baselineValues(9L)
              .go();
    } finally {
      resetSessionOption(ExecConstants.ENABLE_UNION_TYPE_KEY);
    }
  }

  // TODO

  @Test
  public void testUnionExpressionMaterialization() throws Exception {
    alterSession(ExecConstants.ENABLE_UNION_TYPE_KEY, true);
    try {
      testBuilder()
              .sqlQuery("select a + b c from cp.`jsoninput/union/b.json`")
              .ordered()
              .baselineColumns("c")
              .baselineValues(3L)
              .baselineValues(7.0)
              .baselineValues(11.0)
              .go();
    } finally {
      resetSessionOption(ExecConstants.ENABLE_UNION_TYPE_KEY);
    }
  }


  // TODO

  @Test
  public void testSumMultipleBatches() throws Exception {
    File table_dir = dirTestWatcher.makeTestTmpSubDir(Paths.get("multi_batch"));
    BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(new File(table_dir, "a.json")));
    for (int i = 0; i < 10000; i++) {
      os.write("{ type : \"map\", data : { a : 1 } }\n".getBytes());
      os.write("{ type : \"bigint\", data : 1 }\n".getBytes());
    }
    os.flush();
    os.close();

    try {
      testBuilder()
              .sqlQuery("select sum(cast(case when `type` = 'map' then t.data.a else data end as bigint)) `sum` from dfs.tmp.multi_batch t")
              .ordered()
              .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
              .baselineColumns("sum")
              .baselineValues(20000L)
              .go();
    } finally {
      testNoResult("alter session set `exec.enable_union_type` = false");
    }
  }

  // TODO

  @Test
  public void testSumFilesWithDifferentSchema() throws Exception {
    File table_dir = dirTestWatcher.makeTestTmpSubDir(Paths.get("multi_file"));
    BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(new File(table_dir, "a.json")));
    for (int i = 0; i < 10000; i++) {
      os.write("{ type : \"map\", data : { a : 1 } }\n".getBytes());
    }
    os.flush();
    os.close();
    os = new BufferedOutputStream(new FileOutputStream(new File(table_dir, "b.json")));
    for (int i = 0; i < 10000; i++) {
      os.write("{ type : \"bigint\", data : 1 }\n".getBytes());
    }
    os.flush();
    os.close();

    try {
      testBuilder()
              .sqlQuery("select sum(cast(case when `type` = 'map' then t.data.a else data end as bigint)) `sum` from dfs.tmp.multi_file t")
              .ordered()
              .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
              .baselineColumns("sum")
              .baselineValues(20000L)
              .go();
    } finally {
      testNoResult("alter session set `exec.enable_union_type` = false");
    }
  }

  // Done

  @Test
  public void drill_4479() throws Exception {
    try {
      File table_dir = dirTestWatcher.makeTestTmpSubDir(Paths.get("drill_4479"));
      table_dir.mkdir();
      BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(new File(table_dir, "mostlynulls.json")));
      // Create an entire batch of null values for 3 columns
      for (int i = 0 ; i < JSONRecordReader.DEFAULT_ROWS_PER_BATCH; i++) {
        os.write("{\"a\": null, \"b\": null, \"c\": null}".getBytes());
      }
      // Add a row with {bigint,  float, string} values
      os.write("{\"a\": 123456789123, \"b\": 99.999, \"c\": \"Hello World\"}".getBytes());
      os.flush();
      os.close();

      alterSession("store.json.all_text_mode", true);
      testBuilder()
        .sqlQuery("select c, count(*) as cnt from dfs.tmp.drill_4479 t group by c")
        .ordered()
        .baselineColumns("c", "cnt")
        .baselineValues(null, 4096L)
        .baselineValues("Hello World", 1L)
        .go();

      testBuilder()
        .sqlQuery("select a, b, c, count(*) as cnt from dfs.tmp.drill_4479 t group by a, b, c")
        .ordered()
        .baselineColumns("a", "b", "c", "cnt")
        .baselineValues(null, null, null, 4096L)
        .baselineValues("123456789123", "99.999", "Hello World", 1L)
        .go();

      testBuilder()
        .sqlQuery("select max(a) as x, max(b) as y, max(c) as z from dfs.tmp.drill_4479 t")
        .ordered()
        .baselineColumns("x", "y", "z")
        .baselineValues("123456789123", "99.999", "Hello World")
        .go();

    } finally {
      resetSessionOption("store.json.all_text_mode");
    }
  }

  // Done

  @Test
  public void testFlattenEmptyArrayWithAllTextMode() throws Exception {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dirTestWatcher.getRootDir(), "empty_array_all_text_mode.json")))) {
      writer.write("{ \"a\": { \"b\": { \"c\": [] }, \"c\": [] } }");
    }

    try {
      String query = "select flatten(t.a.b.c) as c from dfs.`empty_array_all_text_mode.json` t";

      alterSession("store.json.all_text_mode", true);
      testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .expectsEmptyResultSet()
        .go();

      alterSession("store.json.all_text_mode", false);
      testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .expectsEmptyResultSet()
        .go();

    } finally {
      resetSessionOption("store.json.all_text_mode");
    }
  }

  // TODO: Requires union

  @Test
  public void testFlattenEmptyArrayWithUnionType() throws Exception {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dirTestWatcher.getRootDir(), "empty_array.json")))) {
      writer.write("{ \"a\": { \"b\": { \"c\": [] }, \"c\": [] } }");
    }

    try {
      String query = "select flatten(t.a.b.c) as c from dfs.`empty_array.json` t";

      testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
        .expectsEmptyResultSet()
        .go();

      testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .optionSettingQueriesForTestQuery("alter session set `exec.enable_union_type` = true")
        .optionSettingQueriesForTestQuery("alter session set `store.json.all_text_mode` = true")
        .expectsEmptyResultSet()
        .go();

    } finally {
      testNoResult("alter session reset `store.json.all_text_mode`");
      testNoResult("alter session reset `exec.enable_union_type`");
    }
  }

  @Test // DRILL-5521
  public void testKvgenWithUnionAll() throws Exception {
    String fileName = "map.json";
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dirTestWatcher.getRootDir(), fileName)))) {
      writer.write("{\"rk\": \"a\", \"m\": {\"a\":\"1\"}}");
    }

    String query = String.format("select kvgen(m) as res from (select m from dfs.`%s` union all " +
        "select convert_from('{\"a\" : null}' ,'json') as m from (values(1)))", fileName);
    assertEquals("Row count should match", 2, testSql(query));
  }

  @Test // DRILL-4264
  public void testFieldWithDots() throws Exception {
    String fileName = "table.json";
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dirTestWatcher.getRootDir(), fileName)))) {
      writer.write("{\"rk.q\": \"a\", \"m\": {\"a.b\":\"1\", \"a\":{\"b\":\"2\"}, \"c\":\"3\"}}");
    }

    testBuilder()
      .sqlQuery("select t.m.`a.b` as a,\n" +
        "t.m.a.b as b,\n" +
        "t.m['a.b'] as c,\n" +
        "t.rk.q as d,\n" +
        "t.`rk.q` as e\n" +
        "from dfs.`%s` t", fileName)
      .unOrdered()
      .baselineColumns("a", "b", "c", "d", "e")
      .baselineValues("1", "2", "1", null, "a")
      .go();
  }
}
