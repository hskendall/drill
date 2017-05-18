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
package org.apache.drill.exec.store.mock;

import java.util.List;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.impl.BatchCreator;
import org.apache.drill.exec.physical.impl.OperatorRecordBatch;
import org.apache.drill.exec.physical.impl.ScanBatch;
import org.apache.drill.exec.physical.impl.ScanOperatorExec;
import org.apache.drill.exec.record.CloseableRecordBatch;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.store.RecordReader;
import org.apache.drill.exec.store.RowReader;
import org.apache.drill.exec.store.mock.MockTableDef.MockScanEntry;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class MockScanBatchCreator implements BatchCreator<MockSubScanPOP> {

  @Override
  public CloseableRecordBatch getBatch(FragmentContext context, MockSubScanPOP config, List<RecordBatch> children)
      throws ExecutionSetupException {
    Preconditions.checkArgument(children.isEmpty());
    final List<MockScanEntry> entries = config.getReadEntries();
    if ( entries.get(0).isExtended( ) ) {
      final List<RowReader> readers = Lists.newArrayList();
      for(final MockTableDef.MockScanEntry e : entries) {
        readers.add(new ExtendedMockRecordReader(e));
      }
      ScanOperatorExec scanOp = new ScanOperatorExec(readers.iterator());
      return new OperatorRecordBatch(context, config, scanOp);
    } else {
      final List<RecordReader> readers = Lists.newArrayList();
      for(final MockTableDef.MockScanEntry e : entries) {
        readers.add(new MockRecordReader(context, e));
      }
      return new ScanBatch(config, context, readers.iterator());
    }
  }
}
