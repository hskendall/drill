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
package org.apache.drill.exec.physical.impl.scan;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.ScanBatch;
import org.apache.drill.exec.physical.impl.protocol.BatchAccessor;
import org.apache.drill.exec.physical.impl.protocol.OperatorExec;
import org.apache.drill.exec.physical.impl.protocol.VectorContainerAccessor;

import com.google.common.annotations.VisibleForTesting;

/**
 * Implementation of the revised scan operator that uses a mutator aware of
 * batch sizes. This is the successor to {@link ScanBatch} and should be used
 * by all new scan implementations.
 *
 * <h4>Scanner Framework</h4>
 *
 * Acts as an adapter between the operator protocol and the row reader
 * protocol.
 * <p>
 * The scan operator itself is simply a framework for handling a set of readers;
 * it knows nothing other than the interfaces of the components it works with;
 * delegating all knowledge of schemas, projection, reading and the like to
 * implementations of those interfaces. Because that work is complex, a set
 * of frameworks exist to handle most common use cases, but a specialized reader
 * can create a framework or reader from scratch.
 * <p>
 * Error handling in this class is minimal: the enclosing record batch iterator
 * is responsible for handling exceptions. Error handling relies on the fact
 * that the iterator will call <tt>close()</tt> regardless of which exceptions
 * are thrown.
 *
 * <h4>Protocol</h4>
 *
 * The scanner works directly with two other interfaces
 * <p>
 * The {@link ReaderFactory} implementation provides the set of readers to
 * use. This class can simply maintain a list, or can create the reader on
 * demand.
 * <p>
 * More subtly, the factory also handles projection issues and manages vectors
 * across subsequent readers. A number of factories are available for the most
 * common cases. Extend these to implement a version specific to a data source.
 * <p>
 * The {@link RowBatchReader} is a surprisingly minimal interface that
 * nonetheless captures the essence of reading a result set as a set of batches.
 * The factory implementations mentioned above implement this interface to provide
 * commonly-used services, the most important of which is access to a
 * {#link ResultSetLoader} to write values into value vectors.
 *
 * <h4>Schema Versions</h4>
 * Readers may change schemas from time to time. To track such changes,
 * this implementation tracks a batch schema version, maintained by comparing
 * one schema with the next.
 * <p>
 * Readers can discover columns as they read data, such as with any JSON-based
 * format. In this case, the row set mutator also provides a schema version,
 * but a fine-grained one that changes each time a column is added.
 * <p>
 * The two schema versions serve different purposes and are not interchangeable.
 * For example, if a scan reads two files, both will build up their own schemas,
 * each increasing its internal version number as work proceeds. But, at the
 * end of each batch, the schemas may (and, in fact, should) be identical,
 * which is the schema version downstream operators care about.
 */

public class ScanOperatorExec implements OperatorExec {

  private enum State { START, READER, END, FAILED, CLOSED }

  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ScanOperatorExec.class);

  private final ReaderFactory factory;
  protected final VectorContainerAccessor containerAccessor = new VectorContainerAccessor();
  private State state = State.START;
  protected OperatorContext context;
  private int readerCount;
  private ReaderState readerState;

  public ScanOperatorExec(ReaderFactory factory) {
    this.factory = factory;
  }

  @Override
  public void bind(OperatorContext context) {
    this.context = context;
    factory.bind(context);
  }

  @Override
  public BatchAccessor batchAccessor() { return containerAccessor; }

  @VisibleForTesting
  public OperatorContext context() { return context; }

  @Override
  public boolean buildSchema() {
    assert state == State.START;

    // Spin though readers looking for the first that has enough data
    // to provide a schema. Skips empty, missing or otherwise "null"
    // readers.

    nextAction(true);
    if (state != State.END) {
      return true;
    }

    // Reader count check done here because readers are passed as
    // an iterator, not list. We don't know the count until we've
    // seen EOF from the iterator.

    if (readerCount == 0) {
      // return false; // When empty batches are supported
      throw UserException.executionError( // TODO: Test this path
          new ExecutionSetupException("A scan batch must contain at least one reader."))
        .build(logger);
    }
    return false;
  }

  @Override
  public boolean next() {
    try {
      switch (state) {

      case READER:
        // Read another batch from the list of row readers. Keeps opening,
        // reading from, and closing readers as needed to locate a batch, or
        // until all readers are exhausted. Terminates when a batch is read,
        // or all readers are exhausted.

        nextAction(false);
        return state != State.END;

      case END:
        return false;

      default:
        throw new IllegalStateException("Unexpected state: " + state);
      }
    } catch(Throwable t) {
      state = State.FAILED;
      throw t;
    }
  }

  private void nextAction(boolean schema) {
    for (;;) {

      // If have a reader, read a batch

      if (readerState != null) {
        boolean ok;
        if (schema) {
          ok = readerState.buildSchema();
        } else {
          ok = readerState.next();
        }
        if (ok) {
          break;
        }
        closeReader();
      }

      // Another reader available?

      if (! nextReader()) {
        state = State.END;
        return;
      }
      state = State.READER;

      // Is the reader usable?

      if (! readerState.open()) {
        closeReader();
      }
    }
  }

  /**
   * Open the next available reader, if any, preparing both the
   * reader and row set mutator.
   * @return true if another reader is active, false if no more
   * readers are available
   */

  private boolean nextReader() {

    // Get the next reader, if any.

    RowBatchReader reader = factory.nextReader();
    if (reader == null) {
      containerAccessor.setContainer(null);
      return false;
    }
    readerCount++;

    // Open the reader. This can fail.

    readerState = new ReaderState(this, reader);
    return true;
  }

  /**
   * Close the current reader.
   */

  private void closeReader() {

    try {
      readerState.close();
    } finally {
      readerState = null;
    }
  }

  @Override
  public void cancel() {
    switch (state) {
    case FAILED:
    case CLOSED:
      break;
    default:
      state = State.FAILED;

      // Close early.

      closeAll();
    }
  }

  @Override
  public void close() {
    if (state == State.CLOSED) {
      return;
    }
    closeAll();
  }

  /**
   * Close reader and release row set mutator resources. May be called
   * twice: once when canceling, once when closing. Designed to be
   * safe on the second call.
   */

  private void closeAll() {

    // May throw an unchecked exception

    try {
      if (readerState != null) {
        closeReader();
      }
    } finally {
      factory.close();
      state = State.CLOSED;
    }
  }
}
