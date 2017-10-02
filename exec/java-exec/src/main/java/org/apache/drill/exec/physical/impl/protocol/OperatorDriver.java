package org.apache.drill.exec.physical.impl.protocol;

import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.exec.ops.services.OperatorServices;
import org.apache.drill.exec.record.RecordBatch.IterOutcome;

/**
 * State machine that drives the operator executable. Converts
 * between the iterator protocol and the operator executable protocol.
 * Implemented as a separate class in anticipation of eventually
 * changing the record batch (iterator) protocol.
 */

public class OperatorDriver {
  public enum State { START, SCHEMA, RUN, END, FAILED, CLOSED }


  private OperatorDriver.State state = State.START;
  private final OperatorServices opServices;
  private final OperatorExec operatorExec;
  private final BatchAccessor batchAccessor;
  private int schemaVersion;

  public OperatorDriver(OperatorServices opServicees, OperatorExec opExec) {
    this.opServices = opServicees;
    this.operatorExec = opExec;
    batchAccessor = operatorExec.batchAccessor();
  }

  /**
   * Get the next batch. Performs initialization on the first call.
   * @return the iteration outcome to send downstream
   */

  public IterOutcome next() {
    try {
      switch (state) {
      case START:
        return start();
      case RUN:
        return doNext();
       default:
        OperatorRecordBatch.logger.debug("Extra call to next() in state " + state + ": " + getOperatorLabel());
        return IterOutcome.NONE;
      }
    } catch (UserException e) {
      cancelSilently();
      state = State.FAILED;
      throw e;
    } catch (Throwable t) {
      cancelSilently();
      state = State.FAILED;
      throw UserException.executionError(t)
        .addContext("Exception thrown from", getOperatorLabel())
        .build(OperatorRecordBatch.logger);
    }
  }

  /**
   * Cancels the operator before reaching EOF.
   */

  public void cancel() {
    try {
      switch (state) {
      case START:
      case RUN:
        cancelSilently();
        break;
      default:
        break;
      }
    } finally {
      state = State.FAILED;
    }
  }

 /**
   * Start the operator executor. Bind it to the various contexts.
   * Then start the executor and fetch the first schema.
   * @return result of the first batch, which should contain
   * only a schema, or EOF
   */

  private IterOutcome start() {
    state = State.SCHEMA;
    if (operatorExec.buildSchema()) {
      schemaVersion = batchAccessor.schemaVersion();
      state = State.RUN;
      return IterOutcome.OK_NEW_SCHEMA;
    } else {
      state = State.END;
      return IterOutcome.NONE;
    }
  }

  /**
   * Fetch a record batch, detecting EOF and a new schema.
   * @return the <tt>IterOutcome</tt> for the above cases
   */

  private IterOutcome doNext() {
    if (! operatorExec.next()) {
      state = State.END;
      return IterOutcome.NONE;
    }
    int newVersion = batchAccessor.schemaVersion();
    if (newVersion != schemaVersion) {
      schemaVersion = newVersion;
      return IterOutcome.OK_NEW_SCHEMA;
    }
    return IterOutcome.OK;
  }

  /**
   * Implement a cancellation, and ignore any exception that is
   * thrown. We're already in trouble here, no need to keep track
   * of additional things that go wrong.
   */

  private void cancelSilently() {
    try {
      if (state == State.SCHEMA || state == State.RUN) {
        operatorExec.cancel();
      }
    } catch (Throwable t) {
      // Ignore; we're already in a bad state.
      OperatorRecordBatch.logger.error("Exception thrown from cancel() for " + getOperatorLabel(), t);
    }
  }

  private String getOperatorLabel() {
    return operatorExec.getClass().getCanonicalName();
  }

  public void close() {
    if (state == State.CLOSED) {
      return;
    }
    try {
      operatorExec.close();
    } catch (UserException e) {
      throw e;
    } catch (Throwable t) {
      throw UserException.executionError(t)
        .addContext("Exception thrown from", getOperatorLabel())
        .build(OperatorRecordBatch.logger);
    } finally {
      this.opServices.close();
      state = State.CLOSED;
    }
  }

  public BatchAccessor batchAccessor() {
    return batchAccessor;
  }
}