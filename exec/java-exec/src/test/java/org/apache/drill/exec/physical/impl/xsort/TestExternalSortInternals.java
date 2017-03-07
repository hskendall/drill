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

import java.util.HashMap;
import java.util.Map;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.ops.MetricDef;
import org.apache.drill.exec.ops.OperatorStatReceiver;
import org.apache.drill.exec.physical.impl.xsort.managed.ExternalSortBatch;
import org.apache.drill.exec.physical.impl.xsort.managed.SortConfig;
import org.apache.drill.exec.physical.impl.xsort.managed.SortMemoryManager;
import org.apache.drill.exec.physical.impl.xsort.managed.SortMemoryManager.MergeAction;
import org.apache.drill.exec.physical.impl.xsort.managed.SortMemoryManager.MergeTask;
import org.apache.drill.exec.physical.impl.xsort.managed.SortMetrics;
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

  @Test
  public void testMergeCalcs() {

    // No artificial merge limit

    int mergeLimitConstraint = 100;
    DrillConfig drillConfig = new ConfigBuilder()
        .put(ExecConstants.EXTERNAL_SORT_MERGE_LIMIT, mergeLimitConstraint)
        .build();
    SortConfig sortConfig = new SortConfig(drillConfig);
    // Allow four spill batches, 8 MB each, plus one output of 16
    long memoryLimit = 50 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    // Prime the estimates

    int rowWidth = 300;
    int rowCount = 10000;
    int batchSize = rowWidth * rowCount * 2;

    memManager.updateEstimates(batchSize, rowWidth, rowCount);
    int spillBatchSize = memManager.getSpillBatchSize();
    int mergeBatchSize = memManager.getMergeBatchSize();

    // One in-mem batch, no merging.

    long allocMemory = memoryLimit - mergeBatchSize;
    MergeTask task = memManager.consolidateBatches(allocMemory, 1, 0);
    assertEquals(MergeAction.NONE, task.action);

    // Many in-mem batches, just enough to merge

    allocMemory = memoryLimit - mergeBatchSize;
    int memBatches = (int) (allocMemory / batchSize);
    allocMemory = memBatches * batchSize;
    task = memManager.consolidateBatches(allocMemory, memBatches, 0);
    assertEquals(MergeAction.NONE, task.action);

    // Spills if no room for spill and in-memory batches

    task = memManager.consolidateBatches(allocMemory, memBatches, 1);
    assertEquals(MergeAction.SPILL, task.action);

    // One more in-mem batch: now needs to spill

    memBatches++;
    allocMemory = memBatches * batchSize;
    task = memManager.consolidateBatches(allocMemory, memBatches, 0);
    assertEquals(MergeAction.SPILL, task.action);

    // No spill for various in-mem/spill run combinations

    allocMemory = memoryLimit - spillBatchSize - mergeBatchSize;
    memBatches = (int) (allocMemory / batchSize);
    allocMemory = memBatches * batchSize;
    task = memManager.consolidateBatches(allocMemory, memBatches, 1);
    assertEquals(MergeAction.NONE, task.action);

    allocMemory = memoryLimit - 2 * spillBatchSize - mergeBatchSize;
    memBatches = (int) (allocMemory / batchSize);
    allocMemory = memBatches * batchSize;
    task = memManager.consolidateBatches(allocMemory, memBatches, 2);
    assertEquals(MergeAction.NONE, task.action);

    // No spill if no in-memory, only spill, and spill fits

    long freeMem = memoryLimit - mergeBatchSize;
    int spillBatches = (int) (freeMem / spillBatchSize);
    task = memManager.consolidateBatches(0, 0, spillBatches);
    assertEquals(MergeAction.NONE, task.action);

    // One more and must merge

    task = memManager.consolidateBatches(0, 0, spillBatches + 1);
    assertEquals(MergeAction.MERGE, task.action);
    assertEquals(2, task.count);

    // Two more and will merge more

    task = memManager.consolidateBatches(0, 0, spillBatches + 2);
    assertEquals(MergeAction.MERGE, task.action);
    assertEquals(3, task.count);
  }

  @Test
  public void testMergeLimit() {
    // Constrain merge width
    int mergeLimitConstraint = 5;
    DrillConfig drillConfig = new ConfigBuilder()
        .put(ExecConstants.EXTERNAL_SORT_MERGE_LIMIT, mergeLimitConstraint)
        .build();
    SortConfig sortConfig = new SortConfig(drillConfig);
    // Plenty of memory, memory will not be a limit
    long memoryLimit = 400 * ONE_MEG;
    SortMemoryManager memManager = new SortMemoryManager(sortConfig, memoryLimit);

    // Prime the estimates

    int rowWidth = 300;
    int rowCount = 10000;
    int batchSize = rowWidth * rowCount * 2;

    memManager.updateEstimates(batchSize, rowWidth, rowCount);

    // Pretend merge limit runs, additional in-memory batches

    int memBatchCount = 10;
    int spillRunCount = mergeLimitConstraint;
    long allocMemory = batchSize * memBatchCount;
    MergeTask task = memManager.consolidateBatches(allocMemory, memBatchCount, spillRunCount);
    assertEquals(MergeAction.NONE, task.action);

    // One more run than can merge in one go. But, we have plenty of
    // memory to merge and hold the in-memory batches. So, just merge.

    task = memManager.consolidateBatches(allocMemory, memBatchCount, spillRunCount + 1);
    assertEquals(MergeAction.MERGE, task.action);
    assertEquals(2, task.count);

    // One more runs than can merge in one go, intermediate merge

    task = memManager.consolidateBatches(0, 0, spillRunCount + 1);
    assertEquals(MergeAction.MERGE, task.action);
    assertEquals(2, task.count);

    // Two more spill runs, merge three

    task = memManager.consolidateBatches(0, 0, spillRunCount + 2);
    assertEquals(MergeAction.MERGE, task.action);
    assertEquals(3, task.count);

    // Way more than can merge, limit to the constraint

    task = memManager.consolidateBatches(0, 0, spillRunCount * 3);
    assertEquals(MergeAction.MERGE, task.action);
    assertEquals(mergeLimitConstraint, task.count);
  }

  public static class DummyStats implements OperatorStatReceiver {

    public Map<Integer,Double> stats = new HashMap<>();

    @Override
    public void addLongStat(MetricDef metric, long value) {
      setStat(metric, getStat(metric) + value);
    }

    @Override
    public void addDoubleStat(MetricDef metric, double value) {
      setStat(metric, getStat(metric) + value);
    }

    @Override
    public void setLongStat(MetricDef metric, long value) {
      setStat(metric, value);
    }

    @Override
    public void setDoubleStat(MetricDef metric, double value) {
      setStat(metric, value);
    }

    public double getStat(MetricDef metric) {
      return getStat(metric.metricId());
    }

    private double getStat(int metricId) {
      Double value = stats.get(metricId);
      return value == null ? 0 : value;
    }

    private void setStat(MetricDef metric, double value) {
      setStat(metric.metricId(), value);
    }

    private void setStat(int metricId, double value) {
      stats.put(metricId, value);
    }
  }

  @Test
  public void testMetrics() {
    DummyStats stats = new DummyStats();
    SortMetrics metrics = new SortMetrics(stats);

    // Input stats

    metrics.updateInputMetrics(100, 10_000);
    assertEquals(1, metrics.getInputBatchCount());
    assertEquals(100, metrics.getInputRowCount());
    assertEquals(10_000, metrics.getInputBytes());

    metrics.updateInputMetrics(200, 20_000);
    assertEquals(2, metrics.getInputBatchCount());
    assertEquals(300, metrics.getInputRowCount());
    assertEquals(30_000, metrics.getInputBytes());

    // Buffer memory

    assertEquals(0D, stats.getStat(ExternalSortBatch.Metric.MIN_BUFFER), 0.01);

    metrics.updateMemory(1_000_000);
    assertEquals(1_000_000D, stats.getStat(ExternalSortBatch.Metric.MIN_BUFFER), 0.01);

    metrics.updateMemory(2_000_000);
    assertEquals(1_000_000D, stats.getStat(ExternalSortBatch.Metric.MIN_BUFFER), 0.01);

    metrics.updateMemory(100_000);
    assertEquals(100_000D, stats.getStat(ExternalSortBatch.Metric.MIN_BUFFER), 0.01);

    // Peak batches

    assertEquals(0D, stats.getStat(ExternalSortBatch.Metric.PEAK_BATCHES_IN_MEMORY), 0.01);

    metrics.updatePeakBatches(10);
    assertEquals(10D, stats.getStat(ExternalSortBatch.Metric.PEAK_BATCHES_IN_MEMORY), 0.01);

    metrics.updatePeakBatches(1);
    assertEquals(10D, stats.getStat(ExternalSortBatch.Metric.PEAK_BATCHES_IN_MEMORY), 0.01);

    metrics.updatePeakBatches(20);
    assertEquals(20D, stats.getStat(ExternalSortBatch.Metric.PEAK_BATCHES_IN_MEMORY), 0.01);

    // Merge count

    assertEquals(0D, stats.getStat(ExternalSortBatch.Metric.MERGE_COUNT), 0.01);

    metrics.incrMergeCount();
    assertEquals(1D, stats.getStat(ExternalSortBatch.Metric.MERGE_COUNT), 0.01);

    metrics.incrMergeCount();
    assertEquals(2D, stats.getStat(ExternalSortBatch.Metric.MERGE_COUNT), 0.01);

    // Spill count

    assertEquals(0D, stats.getStat(ExternalSortBatch.Metric.SPILL_COUNT), 0.01);

    metrics.incrSpillCount();
    assertEquals(1D, stats.getStat(ExternalSortBatch.Metric.SPILL_COUNT), 0.01);

    metrics.incrSpillCount();
    assertEquals(2D, stats.getStat(ExternalSortBatch.Metric.SPILL_COUNT), 0.01);

    // Write bytes

    assertEquals(0D, stats.getStat(ExternalSortBatch.Metric.SPILL_MB), 0.01);

    metrics.updateWriteBytes(17 * ONE_MEG + ONE_MEG * 3 / 4);
    assertEquals(17.75D, stats.getStat(ExternalSortBatch.Metric.SPILL_MB), 0.001);
  }
}