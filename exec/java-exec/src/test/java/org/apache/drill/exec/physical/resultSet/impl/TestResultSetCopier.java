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
package org.apache.drill.exec.physical.resultSet.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.impl.protocol.IndirectOutgoingAccessor;
import org.apache.drill.exec.physical.impl.protocol.SingleOutgoingContainerAccessor;
import org.apache.drill.exec.physical.resultSet.ResultSetCopier;
import org.apache.drill.exec.physical.resultSet.ResultSetLoader;
import org.apache.drill.exec.physical.resultSet.RowSetLoader;
import org.apache.drill.exec.physical.resultSet.impl.ResultSetLoaderImpl.ResultSetOptions;
import org.apache.drill.exec.physical.rowSet.RowSet;
import org.apache.drill.exec.physical.rowSet.RowSet.HyperRowSet;
import org.apache.drill.exec.physical.rowSet.RowSetBuilder;
import org.apache.drill.exec.physical.rowSet.RowSetReader;
import org.apache.drill.exec.physical.rowSet.RowSets;
import org.apache.drill.exec.physical.rowSet.TestHyperVectorReaders;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.metadata.MetadataUtils;
import org.apache.drill.exec.record.metadata.SchemaBuilder;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.exec.record.selection.SelectionVector2Builder;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.accessor.ArrayWriter;
import org.apache.drill.exec.vector.accessor.TupleWriter;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.RowSetUtilities;
import org.junit.Test;

public class TestResultSetCopier extends SubOperatorTest {

  private static final TupleMetadata TEST_SCHEMA =
      new SchemaBuilder()
        .add("id", MinorType.INT)
        .add("name", MinorType.VARCHAR)
        .build();

  private static class BaseDataGen {
    protected final TupleMetadata schema;
    protected final ResultSetLoader rsLoader;
    protected final SingleOutgoingContainerAccessor batch;

    public BaseDataGen(TupleMetadata schema) {
      this.schema = schema;
      ResultSetOptions options = new OptionBuilder()
          .setSchema(schema)
          .setVectorCache(new ResultVectorCacheImpl(fixture.allocator()))
          .build();
      rsLoader = new ResultSetLoaderImpl(options);
      batch = new SingleOutgoingContainerAccessor(rsLoader.outputContainer());
    }
  }

  private static class DataGen extends BaseDataGen {

    public DataGen() {
      super(TEST_SCHEMA);
    }

    public void makeBatch(int start, int end) {
      rsLoader.startBatch();
      for (int i = start; i <= end; i++) {
        rsLoader.writer().addRow(i, "Row " + i);
      }
      rsLoader.harvestOutput();
      batch.registerBatch(rsLoader.schemaVersion());
    }
  }

  public static class DataGen2 extends DataGen {
    private final int batchCount = 2;
    private final int batchSize = 5;
    private int batchIndex;

    boolean next() {
      if (batchIndex >= batchCount) {
        return false;
      }
      int start = nextRow();
      makeBatch(start, start + batchSize - 1);
      batchIndex++;
      return true;
    }

    int nextRow() {
      return batchIndex * batchSize + 1;
    }

    int targetRowCount( ) {
      return batchCount * batchSize;
    }
  }

  public static class SchemaChangeGen extends DataGen {
    private int batchIndex;
    public final int batchSize = 5;
    private int schemaVersion = 1;

    public void makeBatch2(int start, int end) {
      rsLoader.startBatch();
      for (int i = start; i <= end; i++) {
        rsLoader.writer().addRow(i, "Row " + i, i * 10);
      }
      rsLoader.harvestOutput();
      batch.registerBatch(rsLoader.schemaVersion());
    }

    public TupleMetadata schema2() {
      return new SchemaBuilder()
          .add("id", MinorType.INT)
          .add("name", MinorType.VARCHAR)
          .add("amount", MinorType.INT)
          .build();
    }

    public void evolveSchema() {
      rsLoader.writer().addColumn(MetadataUtils.newScalar("amount", MinorType.INT, DataMode.REQUIRED));
      schemaVersion = 2;
    }

    public void nextBatch() {
      int start = batchIndex * batchSize + 1;
      int end = start + batchSize - 1;
      if (schemaVersion == 1) {
        makeBatch(start, end);
      } else {
        makeBatch2(start, end);
      }
      batchIndex++;
    }
  }

  private static class NullableGen extends BaseDataGen {

    public NullableGen() {
      super(new SchemaBuilder()
          .add("id", MinorType.INT)
          .addNullable("name", MinorType.VARCHAR)
          .addNullable("amount", MinorType.INT)
          .build());
    }

    public void makeBatch(int start, int end) {
      rsLoader.startBatch();
      RowSetLoader writer = rsLoader.writer();
      for (int i = start; i <= end; i++) {
        writer.start();
        writer.scalar(0).setInt(i);
        if (i % 2 == 0) {
          writer.scalar(1).setString("Row " + i);
        }
        if (i % 3 == 0) {
          writer.scalar(2).setInt(i * 10);
        }
        writer.save();
      }
      rsLoader.harvestOutput();
      batch.registerBatch(rsLoader.schemaVersion());
    }
  }

  private static class ArrayGen extends BaseDataGen {

    public ArrayGen() {
      super(new SchemaBuilder()
          .add("id", MinorType.INT)
          .addArray("name", MinorType.VARCHAR)
          .build());
    }

    public void makeBatch(int start, int end) {
      rsLoader.startBatch();
      RowSetLoader writer = rsLoader.writer();
      ArrayWriter aw = writer.array(1);
      for (int i = start; i <= end; i++) {
        writer.start();
        writer.scalar(0).setInt(i);
        int n = i % 3;
        for (int j = 0; j < n; j++) {
          aw.scalar().setString("Row " + i + "." + j);
        }
        writer.save();
      }
      rsLoader.harvestOutput();
      batch.registerBatch(rsLoader.schemaVersion());
    }
  }

  private static class MapGen extends BaseDataGen {

    public MapGen() {
      super(new SchemaBuilder()
          .add("id", MinorType.INT)
          .addMapArray("map")
            .add("name", MinorType.VARCHAR)
            .add("amount", MinorType.INT)
            .resumeSchema()
          .build());
    }

    public void makeBatch(int start, int end) {
      rsLoader.startBatch();
      RowSetLoader writer = rsLoader.writer();
      ArrayWriter aw = writer.array(1);
      TupleWriter mw = aw.entry().tuple();
      for (int i = start; i <= end; i++) {
        writer.start();
        writer.scalar(0).setInt(i);
        int n = i % 3;
        for (int j = 0; j < n; j++) {
          mw.scalar(0).setString("Row " + i + "." + j);
          mw.scalar(1).setInt(i * 100 + j);
          aw.save();
        }
        writer.save();
      }
      rsLoader.harvestOutput();
      batch.registerBatch(rsLoader.schemaVersion());
    }
  }

  @Test
  public void testBasics() {

    DataGen dataGen = new DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);

    // Nothing should work yet

    try {
      copier.copyAllRows();
      fail();
    } catch (IllegalStateException e) {
      // Expected
    }
    try {
      copier.harvestOutput();
      fail();
    } catch (IllegalStateException e) {
      // Expected
    }

    // Predicates should work

    assertFalse(copier.isCopyPending());
    assertFalse(copier.hasOutputRows());
    assertFalse(copier.isOutputFull());

    // Define a schema and start an output batch.

    copier.startOutputBatch();
    assertFalse(copier.isCopyPending());
    assertFalse(copier.hasOutputRows());
    assertFalse(copier.isOutputFull());

    // Provide an input row

    dataGen.makeBatch(1, 3);
    copier.startInputBatch();
    assertFalse(copier.isCopyPending());
    assertFalse(copier.hasOutputRows());
    assertFalse(copier.isOutputFull());

    // Now can do some actual copying

    while (copier.copyNextRow()) {
      // empty
    }
    assertFalse(copier.isCopyPending());
    assertTrue(copier.hasOutputRows());
    assertFalse(copier.isOutputFull());

    // Get and verify the output batch
    // (Does not free the input batch, we reuse it
    // in the verify step below.)

    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    new RowSetComparison(fixture.wrap(dataGen.batch.container()))
      .verifyAndClear(result);

    // Copier will release the input batch

    copier.close();
  }

  @Test
  public void testImmediateClose() {

    DataGen dataGen = new DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);

    // Close OK before things got started

    copier.close();

    // Second close is benign

    copier.close();
  }

  @Test
  public void testCloseBeforeSchema() {

    DataGen dataGen = new DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);

    // Start batch, no data yet.

    copier.startOutputBatch();

    // Close OK before things data arrives

    copier.close();

    // Second close is benign

    copier.close();
  }

  @Test
  public void testCloseWithData() {

    DataGen dataGen = new DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);

    // Start batch, with data.

    copier.startOutputBatch();
    dataGen.makeBatch(1, 3);
    copier.startInputBatch();
    copier.copyNextRow();

    // Close OK with input and output batch allocated.

    copier.close();

    // Second close is benign

    copier.close();
  }

  /**
   * Test merging multiple batches from the same input
   * source; all batches share the same vectors, hence
   * implicitly the same schema.
   * <p>
   * This copier does not support merging from multiple
   * streams.
   */

  @Test
  public void testMerge() {
    DataGen dataGen = new DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);
    copier.startOutputBatch();

    for (int i = 0; i < 5; i++) {
      int start = i * 3 + 1;
      dataGen.makeBatch(start, start + 2);
      copier.startInputBatch();
      assertFalse(copier.isOutputFull());
      copier.copyAllRows();
      copier.releaseInputBatch();
      assertFalse(copier.isOutputFull());
      assertFalse(copier.isCopyPending());
    }
    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    dataGen.makeBatch(1, 15);
    RowSet expected = RowSets.wrap(dataGen.batch);
    RowSetUtilities.verify(expected, result);

    copier.close();
  }

  @Test
  public void testMultiOutput() {
    DataGen2 dataGen = new DataGen2();
    DataGen validatorGen = new DataGen();

    // Equivalent of operator start() method.

    OptionBuilder options = new OptionBuilder()
        .setRowCountLimit(12);
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch, options);

    // Equivalent of an entire operator run

    int start = 1;
    for (;;) {

      // Equivalent of operator next() method

      copier.startOutputBatch();
      while (!copier.isOutputFull()) {
        copier.releaseInputBatch();
        if (!dataGen.next()) {
          break;
        }
        copier.startInputBatch();
        copier.copyAllRows();
      }
      if (!copier.hasOutputRows()) {
        break;
      }

      // Equivalent of sending downstream

      copier.harvestOutput();
      RowSet result = fixture.wrap(copier.outputContainer());
       int nextRow = dataGen.nextRow();
      validatorGen.makeBatch(start, nextRow - 1);
      RowSet expected = RowSets.wrap(validatorGen.batch);
      RowSetUtilities.verify(expected, result);
      start = nextRow;
    }

    // Ensure more than one output batch.

    assertTrue(start > 1);

    // Ensure all rows generated.

    assertEquals(dataGen.targetRowCount(), start - 1);

    // Simulate operator close();

    copier.close();
  }

  @Test
  public void testCopyRecord() {
    DataGen dataGen = new DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);
    copier.startOutputBatch();

    dataGen.makeBatch(1, 3);
    copier.startInputBatch();
    copier.copyRow(2);
    copier.copyRow(0);
    copier.copyRow(1);
    copier.releaseInputBatch();

    dataGen.makeBatch(4, 6);
    copier.startInputBatch();
    copier.copyRow(1);
    copier.copyRow(0);
    copier.copyRow(2);
    copier.releaseInputBatch();

    RowSet expected = new RowSetBuilder(fixture.allocator(), dataGen.schema)
        .addRow(3, "Row 3")
        .addRow(1, "Row 1")
        .addRow(2, "Row 2")
        .addRow(5, "Row 5")
        .addRow(4, "Row 4")
        .addRow(6, "Row 6")
        .build();
    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    RowSetUtilities.verify(expected, result);

    copier.close();
  }

  @Test
  public void testSchemaChange() {
    SchemaChangeGen dataGen = new SchemaChangeGen();
    SchemaChangeGen verifierGen = new SchemaChangeGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);

    // Copy first batch with first schema

    copier.startOutputBatch();
    dataGen.nextBatch();
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());

    // Second, same schema

    copier.releaseInputBatch();
    dataGen.nextBatch();
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());

    // Plenty of room. But, change the schema.

    copier.releaseInputBatch();
    dataGen.evolveSchema();
    dataGen.nextBatch();
    copier.startInputBatch();
    assertTrue(copier.isOutputFull());

    // Must harvest partial output

    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    verifierGen.makeBatch(1, 2 * dataGen.batchSize - 1);
    RowSet expected = RowSets.wrap(verifierGen.batch);
    RowSetUtilities.verify(expected, result);

    // Start a new batch, implicitly complete pending copy

    copier.startOutputBatch();
    copier.copyAllRows();

    // Add one more of second schema

    copier.releaseInputBatch();
    dataGen.nextBatch();
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());

    copier.harvestOutput();
    result = fixture.wrap(copier.outputContainer());
    verifierGen.evolveSchema();
    verifierGen.makeBatch2(2 * dataGen.batchSize + 1, 4 * dataGen.batchSize - 1);
    expected = RowSets.wrap(verifierGen.batch);
    RowSetUtilities.verify(expected, result);
    assertFalse(copier.isCopyPending());

    copier.close();
  }

  private static class Sv2DataGen {
    protected final ResultSetLoader rsLoader;
    protected final IndirectOutgoingAccessor batch;

    public Sv2DataGen() {
      ResultSetOptions options = new OptionBuilder()
          .setSchema(TEST_SCHEMA)
          .setVectorCache(new ResultVectorCacheImpl(fixture.allocator()))
          .build();
      rsLoader = new ResultSetLoaderImpl(options);
      batch = new IndirectOutgoingAccessor(rsLoader.outputContainer());
    }

    public void makeBatch(int start, int end) {
      rsLoader.startBatch();
      for (int i = start; i <= end; i++) {
        rsLoader.writer().addRow(i, "Row " + i);
      }
      rsLoader.harvestOutput();
      VectorContainer container = rsLoader.outputContainer();
      SelectionVector2Builder sv2Builder =
          new SelectionVector2Builder(container);
      for (int i = 0; i < 5; i++) {
        sv2Builder.setNext(10 - 2 * i - 1);
      }
      container.buildSchema(SelectionVectorMode.TWO_BYTE);
      batch.registerBatch(sv2Builder.build());
    }
  }

  // TODO: Test with two consecutive schema changes in
  // same input batch: once with rows pending, another without.

  @Test
  public void testSV2() {
    Sv2DataGen dataGen = new Sv2DataGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);

    copier.startOutputBatch();
    dataGen.makeBatch(1, 10);

    // Pick out every other record, in descending
    // order.

    assertEquals(5, dataGen.batch.rowCount());

    copier.startInputBatch();
    copier.copyAllRows();
    copier.releaseInputBatch();

    RowSet expected = new RowSetBuilder(fixture.allocator(), TEST_SCHEMA)
        .addRow(10, "Row 10")
        .addRow(8, "Row 8")
        .addRow(6, "Row 6")
        .addRow(4, "Row 4")
        .addRow(2, "Row 2")
        .build();
    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    RowSetUtilities.verify(expected, result);

    copier.close();
  }

  @Test
  public void testSV4() {
    HyperRowSet hyperSet = TestHyperVectorReaders.buildRequiredIntHyperBatch(fixture.allocator());
    VectorContainer container = hyperSet.container();
    IndirectOutgoingAccessor filtered = new IndirectOutgoingAccessor(container);
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), filtered);

    copier.startOutputBatch();
    filtered.registerBatch(hyperSet.getSv4());

    assertEquals(hyperSet.rowCount(), filtered.rowCount());

    copier.startInputBatch();
    copier.copyAllRows();

    RowSet expected = TestHyperVectorReaders.expectedRequiredIntBatch(fixture.allocator());
    RowSetUtilities.verify(expected, hyperSet);

    copier.releaseInputBatch();
    copier.close();
  }

  @Test
  public void testNullable() {
    NullableGen dataGen = new NullableGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);
    copier.startOutputBatch();

    dataGen.makeBatch(1, 10);
    copier.startInputBatch();
    copier.copyAllRows();

    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    RowSet expected = RowSets.wrap(dataGen.batch);
    RowSetUtilities.verify(expected, result);

    copier.close();
  }

  @Test
  public void testArrays() {
    ArrayGen dataGen = new ArrayGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);
    copier.startOutputBatch();

    dataGen.makeBatch(1, 5);
    copier.startInputBatch();
    copier.copyAllRows();

    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    RowSet expected = RowSets.wrap(dataGen.batch);
    RowSetUtilities.verify(expected, result);

    copier.close();
  }

  @Test
  public void testMaps() {
    MapGen dataGen = new MapGen();
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch);
    copier.startOutputBatch();

    dataGen.makeBatch(1, 5);
    copier.startInputBatch();
    copier.copyAllRows();

    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    RowSet expected = RowSets.wrap(dataGen.batch);
    RowSetUtilities.verify(expected, result);

    copier.close();
  }

  @Test
  public void testUnions() {
    // TODO
  }

  private static class BigVarcharDataGen {
    protected final TupleMetadata schema;
    protected final ResultSetLoader rsLoader;
    protected final SingleOutgoingContainerAccessor batch;

    // Odd sized data value to cause overflow at vector end
    byte[] value = new byte[473];
    int rowCount;

    public BigVarcharDataGen() {
      this.schema = TEST_SCHEMA;
     ResultSetOptions options = new OptionBuilder()
          .setSchema(schema)
          .setVectorCache(new ResultVectorCacheImpl(fixture.allocator()))
          // 16MB / .5K = 32K, these batches are half that size
          .setRowCountLimit(16 * 1024)
          .build();
      rsLoader = new ResultSetLoaderImpl(options);
      batch = new SingleOutgoingContainerAccessor(rsLoader.outputContainer());
      Arrays.fill(value, (byte) 'X');
    }

    // Get a set of non-overflowed batches that will, however,
    // cause the copier to overflow due to no row limit, and
    // odd row sizes.

    public boolean next() {
      if (rsLoader.batchCount() >= 10) {
        return false;
      }
      rsLoader.startBatch();
      RowSetLoader writer = rsLoader.writer();
      while (!writer.isFull()) {
        writer.addRow(++rowCount, value);
      }
      rsLoader.harvestOutput();
      batch.registerBatch(rsLoader.schemaVersion());
      return true;
    }
  }

  @Test
  public void testOverflow() {
    BigVarcharDataGen dataGen = new BigVarcharDataGen();
    OptionBuilder options = new OptionBuilder()
        .setRowCountLimit(ValueVector.MAX_ROW_COUNT);
    ResultSetCopier copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch, options);

    int outputRowCount = 0;
    for (;;) {

      // Equivalent of operator next() method

      copier.startOutputBatch();
      while (!copier.isOutputFull()) {
        copier.releaseInputBatch();
        if (!dataGen.next()) {
          break;
        }
        copier.startInputBatch();
        copier.copyAllRows();
      }
      if (!copier.hasOutputRows()) {
        break;
      }

      // Equivalent of sending downstream

      copier.harvestOutput();
      RowSet result = fixture.wrap(copier.outputContainer());
      RowSetReader reader = result.reader();
      while (reader.next()) {
        assertEquals(++outputRowCount, reader.scalar(0).getInt());
        assertEquals(dataGen.value.length, reader.scalar(1).getString().length());
      }
      result.clear();
    }
    assertEquals(dataGen.rowCount, outputRowCount);

    copier.close();
    dataGen.rsLoader.close();
  }

  private static class SparseFilterDataGen {
    protected final ResultSetLoader rsLoader;
    protected final IndirectOutgoingAccessor batch;

    public SparseFilterDataGen() {
      ResultSetOptions options = new OptionBuilder()
          .setSchema(TEST_SCHEMA)
          .setVectorCache(new ResultVectorCacheImpl(fixture.allocator()))
          .build();
      rsLoader = new ResultSetLoaderImpl(options);
      batch = new IndirectOutgoingAccessor(rsLoader.outputContainer());
    }

    // Get a set of non-overflowed batches that will, however,
    // cause the copier to overflow due to no row limit, and
    // odd row sizes.

    public void next(int batchSize, boolean full) {
      rsLoader.startBatch();
      for (int i = 0; i < batchSize; i++) {
        rsLoader.writer().addRow(i, "Row " + i);
      }
      rsLoader.harvestOutput();
      VectorContainer container = rsLoader.outputContainer();
      SelectionVector2Builder sv2Builder =
          new SelectionVector2Builder(container);
      if (full) {
        for (int i = 0; i < batchSize; i++) {
          sv2Builder.setNext(i);
        }
      } else {
        for (int i = 0; i < batchSize/2; i++) {
          sv2Builder.setNext(i * 2);
        }
      }
      container.buildSchema(SelectionVectorMode.TWO_BYTE);
      batch.registerBatch(sv2Builder.build());
    }
  }

  final int bigInputSize = 100;
  final int outputSize = 60;
  final int minBulkCopySize = outputSize / 2 + 1;

  /**
   * Test an initial bulk copy batch larger than the
   * target row size. Immediately fills the output batch.
   */

  @Test
  public void testLargeBulkCopyOnFirst() {

    SparseFilterDataGen dataGen = new SparseFilterDataGen();
    OptionBuilder options = new OptionBuilder()
        .setRowCountLimit(outputSize);
    ResultSetCopierImpl copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch, options);
    copier.allowDirectTransfer(true);

    // Start: do a bulk copy: no SV and batch size > target/2

    copier.startOutputBatch();
    dataGen.next(bigInputSize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    copier.harvestOutput();
    assertEquals(1, copier.bulkCopyCount());
    VectorContainer container = copier.outputContainer();
    assertEquals(bigInputSize, container.getRecordCount());
    container.zeroVectors();

    // Filtered batch. Regular copy.

    copier.startOutputBatch();
    copier.releaseInputBatch();
    dataGen.next(bigInputSize, false);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());
    assertEquals(1, copier.bulkCopyCount());

    copier.harvestOutput();
    container = copier.outputContainer();
    assertEquals(bigInputSize / 2, container.getRecordCount());
    container.zeroVectors();

    copier.close();
  }

  /**
   * Test that a bulk copy leaves the result set loader in
   * a sane state to append more data in the second batch.
   */

  @Test
  public void testBulkCopyAndAppend() {

    SparseFilterDataGen dataGen = new SparseFilterDataGen();
    OptionBuilder options = new OptionBuilder()
        .setRowCountLimit(outputSize);
    ResultSetCopierImpl copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch, options);
    copier.allowDirectTransfer(true);

    // Large enough for bulk copy, smaller than full batch

    copier.startOutputBatch();
    dataGen.next(minBulkCopySize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());
    assertEquals(1, copier.bulkCopyCount());

    // We can now append a small batch on top of the bulk
    // copy. Use a filtered batch.

    copier.releaseInputBatch();
    dataGen.next(minBulkCopySize, false);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());

    // Single batch, with second appended.

    copier.harvestOutput();
    VectorContainer container = copier.outputContainer();
    assertEquals(minBulkCopySize * 3 / 2, container.getRecordCount());

    // Sanity check of data.

    RowSet result = fixture.wrap(container);
    result.print();
    RowSetReader reader = result.reader();
    for (int i = 0; i < minBulkCopySize; i++) {
      reader.next();
      assertEquals(i, reader.scalar(0).getInt());
      assertEquals("Row " + i, reader.scalar(1).getString());
    }
    for (int i = minBulkCopySize; i < 2 * minBulkCopySize; i += 2) {
      reader.next();
      assertEquals(i, reader.scalar(0).getInt());
      assertEquals("Row " + i, reader.scalar(1).getString());
    }
    result.clear();

    copier.close();
  }

  /**
   * Test multiple bulk copies in a row.
   */
  @Test
  public void testMultipleBulkCopy() {

    SparseFilterDataGen dataGen = new SparseFilterDataGen();
    OptionBuilder options = new OptionBuilder()
        .setRowCountLimit(outputSize);
    ResultSetCopierImpl copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch, options);
    copier.allowDirectTransfer(true);

    // Large enough for bulk copy, smaller than full batch

    copier.startOutputBatch();
    dataGen.next(minBulkCopySize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());
    assertEquals(1, copier.bulkCopyCount());

    // Another. Forces flush of the above.

    copier.releaseInputBatch();
    dataGen.next(minBulkCopySize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    assertEquals(2, copier.bulkCopyCount());
    copier.harvestOutput();
    VectorContainer container = copier.outputContainer();
    assertEquals(minBulkCopySize, container.getRecordCount());
    container.zeroVectors();
    assertTrue(copier.isCopyPending());

    // Another batch, flushes the above.

    copier.startOutputBatch();
    copier.releaseInputBatch();
    assertFalse(copier.isOutputFull());
    assertEquals(2, copier.bulkCopyCount());
    copier.harvestOutput();
    container = copier.outputContainer();
    assertEquals(minBulkCopySize, container.getRecordCount());
    container.zeroVectors();
    assertFalse(copier.isCopyPending());

    copier.close();
  }

  // Switch to/from SV2
  // Switch SV2 size
  // Non-bulk flushed by bulk
  // Small batch consolidation

  @Test
  public void testBulkCopy() {

    SparseFilterDataGen dataGen = new SparseFilterDataGen();
    OptionBuilder options = new OptionBuilder()
        .setRowCountLimit(outputSize);
    ResultSetCopierImpl copier = new ResultSetCopierImpl(
        fixture.allocator(), dataGen.batch, options);
    copier.allowDirectTransfer(true);

    // Start: do a bulk copy: no SV and batch size > target/2

    copier.startOutputBatch();
    dataGen.next(bigInputSize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    copier.harvestOutput();
    RowSet result = fixture.wrap(copier.outputContainer());
    assertEquals(1, copier.bulkCopyCount());
    assertEquals(bigInputSize, result.rowCount());
    result.clear();

    // Filtered batch

    copier.startOutputBatch();
    copier.releaseInputBatch();
    dataGen.next(bigInputSize, false);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());
    assertEquals(1, copier.bulkCopyCount());

    // Another filtered batch, gives output, leaves input
    // still to be copied

    copier.releaseInputBatch();
    dataGen.next(bigInputSize, false);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    copier.harvestOutput();
    assertEquals(1, copier.bulkCopyCount());
    result = fixture.wrap(copier.outputContainer());
    assertEquals(outputSize, result.rowCount());
    result.clear();

    // Unfiltered batch, flushes remaining 40 rows, then
    // does a bulk copy.

    copier.startOutputBatch();
    copier.releaseInputBatch();
    dataGen.next(minBulkCopySize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    assertEquals(2, copier.bulkCopyCount());
    copier.harvestOutput();
    result = fixture.wrap(copier.outputContainer());
    assertEquals(bigInputSize - outputSize, result.rowCount());
    result.clear();
    assertTrue(copier.isCopyPending());

    // Next output batch: does the actual bulk copy.

    copier.startOutputBatch();
    assertFalse(copier.isOutputFull());
    assertTrue(copier.hasOutputRows());
    assertFalse(copier.isCopyPending());

    // Unfiltered, min-size batch, bulk copy

    copier.startOutputBatch();
    copier.releaseInputBatch();
    dataGen.next(minBulkCopySize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    copier.harvestOutput();
    result = fixture.wrap(copier.outputContainer());
    assertEquals(3, copier.bulkCopyCount());
    assertEquals(minBulkCopySize, result.rowCount());
    result.clear();
    assertFalse(copier.isCopyPending());

    // Small unfiltered batch: copied, no output

    final int tinyInputSize = outputSize / 3;
    copier.startOutputBatch();
    copier.releaseInputBatch();
    dataGen.next(tinyInputSize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());

    // Another, this one same size, but filtered

    copier.releaseInputBatch();
    dataGen.next(tinyInputSize * 2, false);
    copier.startInputBatch();
    copier.copyAllRows();
    assertFalse(copier.isOutputFull());

    // Third, we get an output, shows small batch consolidation

    copier.releaseInputBatch();
    dataGen.next(tinyInputSize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    copier.harvestOutput();
    result = fixture.wrap(copier.outputContainer());
    assertEquals(3, copier.bulkCopyCount());
    assertEquals(outputSize, result.rowCount());
    result.clear();
    assertFalse(copier.isCopyPending());

    // Finally, one more large, unfiltered batch. Since no input
    // or copy is pending, should immediately do a bulk copy.

    copier.startOutputBatch();
    dataGen.next(bigInputSize, true);
    copier.startInputBatch();
    copier.copyAllRows();
    assertTrue(copier.isOutputFull());
    copier.harvestOutput();
    result = fixture.wrap(copier.outputContainer());
    assertEquals(4, copier.bulkCopyCount());
    assertEquals(bigInputSize, result.rowCount());
    result.clear();

    copier.close();
    dataGen.rsLoader.close();
  }
}
