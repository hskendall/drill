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
package org.apache.drill.exec.store.http;

import java.util.List;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ops.ExecutorFragmentContext;
import org.apache.drill.exec.physical.base.GroupScan;
import org.apache.drill.exec.physical.impl.BatchCreator;
import org.apache.drill.exec.physical.impl.ScanBatch;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.store.RecordReader;
import org.apache.drill.shaded.guava.com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpScanBatchCreator implements BatchCreator<HttpSubScan> {
  private static final Logger logger = LoggerFactory.getLogger(HttpScanBatchCreator.class);

  @Override
  public ScanBatch getBatch(ExecutorFragmentContext context, HttpSubScan subScan, List<RecordBatch> children) throws ExecutionSetupException {
    logger.debug("getBatch called");
    HttpStoragePluginConfig config = subScan.getConfig();
    List<RecordReader> readers = Lists.newArrayList();
    List<SchemaPath> columns = null;
    try {
      if ((columns = subScan.getColumns()) == null) {
        columns = GroupScan.ALL_COLUMNS;
      }
      readers.add(new HttpRecordReader(context,columns, config, subScan));
    } catch (Exception e) {
      throw new ExecutionSetupException(e);
    }
    return new ScanBatch(subScan, context, readers);
  }

  @Override
  public String toString() {
    return "[" + this.getClass().getSimpleName() + "]";
  }
}
