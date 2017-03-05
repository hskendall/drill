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
package org.apache.drill.exec.physical.impl.xsort;

import static org.junit.Assert.*;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.physical.impl.xsort.managed.SortConfig;
import org.apache.drill.exec.physical.impl.xsort.managed.SortMemoryManager;
import org.apache.drill.test.ConfigBuilder;
import org.apache.drill.test.DrillTest;
import org.junit.Test;

public class TestExternalSortInternals extends DrillTest {

  private static final int ONE_MEG = 1024 * 1024;

  /**
   * Verify defaults configured in drill-override.conf.
   */
  @Test
  public void testConfigDefaults() {
    DrillConfig drillConfig = DrillConfig.create();
    SortConfig sortConfig = new SortConfig(drillConfig);
    // Zero means no artificial limit
    assertEquals(0, sortConfig.maxMemory());
    // Zero mapped to large number
    assertEquals(Integer.MAX_VALUE, sortConfig.mergeLimit());
    // Default size: 256 MiB
    assertEquals(256 * ONE_MEG, sortConfig.spillFileSize());
    // Default size: 8 MiB
    assertEquals(8 * ONE_MEG, sortConfig.spillBatchSize());
    // Default size: 16 MiB
    assertEquals(16 * ONE_MEG, sortConfig.mergeBatchSize());
  }

  /**
   * Verify that the various constants do, in fact, map to the
   * expected properties, and that the properties are overridden.
   */
  @Test
  public void testConfigOverride() {
    // Verify the various HOCON ways of setting memory
    DrillConfig drillConfig = new ConfigBuilder()
        .put(ExecConstants.EXTERNAL_SORT_MAX_MEMORY, "2000K")
        .put(ExecConstants.EXTERNAL_SORT_MERGE_LIMIT, 10)
        .put(ExecConstants.EXTERNAL_SORT_SPILL_FILE_SIZE, "10M")
        .put(ExecConstants.EXTERNAL_SORT_SPILL_BATCH_SIZE, 500_000)
        .put(ExecConstants.EXTERNAL_SORT_MERGE_BATCH_SIZE, 600_000)
        .build();
    SortConfig sortConfig = new SortConfig(drillConfig);
    assertEquals(2000 * 1024, sortConfig.maxMemory());
    assertEquals(10, sortConfig.mergeLimit());
    assertEquals(10 * ONE_MEG, sortConfig.spillFileSize());
    assertEquals(500_000, sortConfig.spillBatchSize());
    assertEquals(600_000, sortConfig.mergeBatchSize());
  }

  /**
   * Some properties have hard-coded limits. Verify these limits.
   */
  @Test
  public void testConfigLimits() {
    DrillConfig drillConfig = new ConfigBuilder()
        .put(ExecConstants.EXTERNAL_SORT_MERGE_LIMIT, SortConfig.MIN_MERGE_LIMIT - 1)
        .put(ExecConstants.EXTERNAL_SORT_SPILL_FILE_SIZE, SortConfig.MIN_SPILL_FILE_SIZE - 1)
        .put(ExecConstants.EXTERNAL_SORT_SPILL_BATCH_SIZE, SortConfig.MIN_SPILL_BATCH_SIZE - 1)
        .put(ExecConstants.EXTERNAL_SORT_MERGE_BATCH_SIZE, SortConfig.MIN_MERGE_BATCH_SIZE - 1)
        .build();
    SortConfig sortConfig = new SortConfig(drillConfig);
    assertEquals(SortConfig.MIN_MERGE_LIMIT, sortConfig.mergeLimit());
    assertEquals(SortConfig.MIN_SPILL_FILE_SIZE, sortConfig.spillFileSize());
    assertEquals(SortConfig.MIN_SPILL_BATCH_SIZE, sortConfig.spillBatchSize());
    assertEquals(SortConfig.MIN_MERGE_BATCH_SIZE, sortConfig.mergeBatchSize());
  }

  @Test
  public void testMemoryManagerBasics() {
    DrillConfig drillConfig = DrillConfig.create();
    SortConfig sortConfig = new SortConfig(drillConfig);
    long memoryLimit = 50 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    // Basic setup

    assertEquals(sortConfig.spillBatchSize(), memManager.getPreferredSpillBatchSize());
    assertEquals(sortConfig.mergeBatchSize(), memManager.getPreferredMergeBatchSize());
    assertEquals(memoryLimit, memManager.getMemoryLimit());

    // Nice simple batch: 6 MB in size, 300 byte rows, vectors half full
    // so 10000 rows. Sizes chosen so that spill and merge batch record
    // stay below the limit of 64K.

    int rowWidth = 300;
    int rowCount = 10000;
    int batchSize = rowWidth * rowCount * 2;

    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    verifyCalcs(sortConfig, memoryLimit, memManager, batchSize, rowWidth, rowCount);

    // Zero rows - no update

    memManager.updateEstimates(batchSize, rowWidth, 0);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());

    // Larger batch size, update batch size

    rowCount = 20000;
    batchSize = rowWidth * rowCount * 2;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    verifyCalcs(sortConfig, memoryLimit, memManager, batchSize, rowWidth, rowCount);

    // Smaller batch size: no change

    rowCount = 5000;
    int lowBatchSize = rowWidth * rowCount * 2;
    memManager.updateEstimates(lowBatchSize, rowWidth, rowCount);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());

    // Different batch density, update batch size

    rowCount = 10000;
    batchSize = rowWidth * rowCount * 5;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    verifyCalcs(sortConfig, memoryLimit, memManager, batchSize, rowWidth, rowCount);

    // Smaller row size, no update

    int lowRowWidth = 200;
    rowCount = 10000;
    lowBatchSize = rowWidth * rowCount * 2;
    memManager.updateEstimates(lowBatchSize, lowRowWidth, rowCount);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());

    // Larger row size, updates calcs

    rowWidth = 400;
    rowCount = 10000;
    lowBatchSize = rowWidth * rowCount * 2;
    memManager.updateEstimates(lowBatchSize, rowWidth, rowCount);
    verifyCalcs(sortConfig, memoryLimit, memManager, batchSize, rowWidth, rowCount);

    // EOF: very low density

    memManager.updateEstimates(lowBatchSize, rowWidth, 5);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());
  }

  private void verifyCalcs(SortConfig sortConfig, long memoryLimit, SortMemoryManager memManager, int batchSize,
      int rowWidth, int rowCount) {

    assertFalse(memManager.mayOverflow());

    // Row and batch sizes should be exact

    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());

    // Spill sizes will be rounded, but within reason.

    int count = sortConfig.spillBatchSize() / rowWidth;
    assertTrue(count >= memManager.getSpillBatchRowCount());
    assertTrue(count/2 <= memManager.getSpillBatchRowCount());
    int spillSize = memManager.getSpillBatchRowCount() * rowWidth;
    assertTrue(spillSize <= memManager.getSpillBatchSize());
    assertTrue(spillSize >= memManager.getSpillBatchSize()/2);
    assertEquals(memoryLimit - memManager.getSpillBatchSize(), memManager.getBufferMemoryLimit());

    // Merge sizes will also be rounded, within reason.

    count = sortConfig.mergeBatchSize() / rowWidth;
    assertTrue(count >= memManager.getMergeBatchRowCount());
    assertTrue(count/2 <= memManager.getMergeBatchRowCount());
    int mergeSize = memManager.getMergeBatchRowCount() * rowWidth;
    assertTrue(mergeSize <= memManager.getMergeBatchSize());
    assertTrue(mergeSize >= memManager.getMergeBatchSize()/2);
    assertEquals(memoryLimit - memManager.getMergeBatchSize(), memManager.getMergeMemoryLimit());
  }

  @Test
  public void testSmallRows() {
    DrillConfig drillConfig = DrillConfig.create();
    SortConfig sortConfig = new SortConfig(drillConfig);
    long memoryLimit = 100 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    // Zero-length row, round to 10

    int rowWidth = 0;
    int rowCount = 10000;
    int batchSize = rowCount * 2;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    assertEquals(10, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());

    // Truncate spill, merge batch row count

    assertEquals(Character.MAX_VALUE, memManager.getSpillBatchRowCount());
    assertEquals(Character.MAX_VALUE, memManager.getMergeBatchRowCount());

    // But leave batch sizes at their defaults

    assertEquals(sortConfig.spillBatchSize(), memManager.getPreferredSpillBatchSize());
    assertEquals(sortConfig.mergeBatchSize(), memManager.getPreferredMergeBatchSize());

    // Small, but non-zero, row

    rowWidth = 20;
    rowCount = 10000;
    batchSize = rowWidth * rowCount;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());

    // Truncate spill, merge batch row count

    assertEquals(Character.MAX_VALUE, memManager.getSpillBatchRowCount());
    assertEquals(Character.MAX_VALUE, memManager.getMergeBatchRowCount());

    // But leave batch sizes at their defaults

    assertEquals(sortConfig.spillBatchSize(), memManager.getPreferredSpillBatchSize());
    assertEquals(sortConfig.mergeBatchSize(), memManager.getPreferredMergeBatchSize());
  }

  @Test
  public void testLowMemory() {
    DrillConfig drillConfig = DrillConfig.create();
    SortConfig sortConfig = new SortConfig(drillConfig);
    long memoryLimit = 10 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    // Tight squeeze, but can be made to work.
    // Input batches are a quarter of memory.

    int rowWidth = 1000;
    int rowCount = (int) (memoryLimit / 4 / rowWidth);
    int batchSize = rowCount * rowWidth;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());
    assertFalse(memManager.mayOverflow());

    // Spill, merge batches should be constrained

    int spillBatchSize = memManager.getSpillBatchSize();
    assertTrue(spillBatchSize < memManager.getPreferredSpillBatchSize());
    assertTrue(spillBatchSize >= rowWidth);
    assertTrue(spillBatchSize <= memoryLimit / 3);
    assertTrue(spillBatchSize + 2 * batchSize <= memoryLimit);
    assertTrue(spillBatchSize / rowWidth >= memManager.getSpillBatchRowCount());

    int mergeBatchSize = memManager.getMergeBatchSize();
    assertTrue(mergeBatchSize < memManager.getPreferredMergeBatchSize());
    assertTrue(mergeBatchSize >= rowWidth);
    assertTrue(mergeBatchSize + 2 * spillBatchSize <= memoryLimit);
    assertTrue(mergeBatchSize / rowWidth >= memManager.getMergeBatchRowCount());

    // Should spill after just two batches

    assertFalse(memManager.isSpillNeeded(0, batchSize));
    assertFalse(memManager.isSpillNeeded(batchSize, batchSize));
    assertTrue(memManager.isSpillNeeded(2 * batchSize, batchSize));

    // Tighter squeeze, but can be made to work.
    // Input batches are 3/8 of memory; two fill 3/4,
    // but small spill and merge batches allow progress.

    rowWidth = 1000;
    rowCount = (int) (memoryLimit * 3 / 8 / rowWidth);
    batchSize = rowCount * rowWidth;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());
    assertFalse(memManager.mayOverflow());

    // Spill, merge batches should be constrained

    spillBatchSize = memManager.getSpillBatchSize();
    assertTrue(spillBatchSize < memManager.getPreferredSpillBatchSize());
    assertTrue(spillBatchSize >= rowWidth);
    assertTrue(spillBatchSize <= memoryLimit / 3);
    assertTrue(spillBatchSize + 2 * batchSize <= memoryLimit);
    assertTrue(memManager.getSpillBatchRowCount() > 1);
    assertTrue(spillBatchSize / rowWidth >= memManager.getSpillBatchRowCount());

    mergeBatchSize = memManager.getMergeBatchSize();
    assertTrue(mergeBatchSize < memManager.getPreferredMergeBatchSize());
    assertTrue(mergeBatchSize >= rowWidth);
    assertTrue(mergeBatchSize + 2 * spillBatchSize <= memoryLimit);
    assertTrue(memManager.getMergeBatchRowCount() > 1);
    assertTrue(mergeBatchSize / rowWidth >= memManager.getMergeBatchRowCount());
  }

  @Test
  public void testExtremeLowMemory() {
    DrillConfig drillConfig = DrillConfig.create();
    SortConfig sortConfig = new SortConfig(drillConfig);
    long memoryLimit = 10 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    // Jumbo row size, works with one row per batch. Minimum is to have two
    // input rows and a spill row, or two spill rows and a merge row.
    // Have to back off the exact size a bit to allow for internal fragmentation
    // in the merge and output batches.

    int rowWidth = (int) (memoryLimit / 3 * 0.75);
    int rowCount = 1;
    int batchSize = rowWidth;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    assertEquals(rowWidth, memManager.getRowWidth());
    assertEquals(batchSize, memManager.getInputBatchSize());
    assertFalse(memManager.mayOverflow());

    int spillBatchSize = memManager.getSpillBatchSize();
    assertTrue(spillBatchSize >= rowWidth);
    assertTrue(spillBatchSize <= memoryLimit / 3);
    assertTrue(spillBatchSize + 2 * batchSize <= memoryLimit);
    assertEquals(1, memManager.getSpillBatchRowCount());

    int mergeBatchSize = memManager.getMergeBatchSize();
    assertTrue(mergeBatchSize >= rowWidth);
    assertTrue(mergeBatchSize + 2 * spillBatchSize <= memoryLimit);
    assertEquals(1, memManager.getMergeBatchRowCount());

    // Should spill after just two rows

    assertFalse(memManager.isSpillNeeded(0, batchSize));
    assertFalse(memManager.isSpillNeeded(batchSize, batchSize));
    assertTrue(memManager.isSpillNeeded(2 * batchSize, batchSize));

    // In trouble now, can't fit even three rows.

    rowWidth = (int) (memoryLimit / 2);
    rowCount = 1;
    batchSize = rowWidth;
    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    assertTrue(memManager.mayOverflow());
  }

  @Test
  public void testConfigConstraints() {
    int memConstaint = 40 * ONE_MEG;
    int batchSizeConstaint = ONE_MEG / 2;
    int mergeSizeConstaint = ONE_MEG;
    DrillConfig drillConfig = new ConfigBuilder()
        .put(ExecConstants.EXTERNAL_SORT_MAX_MEMORY, memConstaint)
        .put(ExecConstants.EXTERNAL_SORT_SPILL_BATCH_SIZE, batchSizeConstaint)
        .put(ExecConstants.EXTERNAL_SORT_MERGE_BATCH_SIZE, mergeSizeConstaint)
        .build();
    SortConfig sortConfig = new SortConfig(drillConfig);
    long memoryLimit = 50 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    assertEquals(batchSizeConstaint, memManager.getPreferredSpillBatchSize());
    assertEquals(mergeSizeConstaint, memManager.getPreferredMergeBatchSize());
    assertEquals(memConstaint, memManager.getMemoryLimit());

    int rowWidth = 300;
    int rowCount = 10000;
    int batchSize = rowWidth * rowCount * 2;

    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    verifyCalcs(sortConfig, memConstaint, memManager, batchSize, rowWidth, rowCount);
  }

  @Test
  public void testMemoryDynamics() {
    DrillConfig drillConfig = DrillConfig.create();
    SortConfig sortConfig = new SortConfig(drillConfig);
    long memoryLimit = 50 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    int rowWidth = 300;
    int rowCount = 10000;
    int batchSize = rowWidth * rowCount * 2;

    memManager.updateEstimates(batchSize, rowWidth, rowCount);

    int spillBatchSize = memManager.getSpillBatchSize();

    // Test various memory fill levels

    assertFalse(memManager.isSpillNeeded(0, batchSize));
    assertFalse(memManager.isSpillNeeded(2 * batchSize, batchSize));
    assertTrue(memManager.isSpillNeeded(memoryLimit - spillBatchSize + 1, batchSize));

    // Similar, but for an in-memory merge

    assertTrue(memManager.hasMemoryMergeCapacity(memoryLimit - ONE_MEG, ONE_MEG - 1));
    assertTrue(memManager.hasMemoryMergeCapacity(memoryLimit - ONE_MEG, ONE_MEG));
    assertFalse(memManager.hasMemoryMergeCapacity(memoryLimit - ONE_MEG, ONE_MEG + 1));
  }
}
