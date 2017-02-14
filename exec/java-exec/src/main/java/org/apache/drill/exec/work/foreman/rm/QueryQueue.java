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
package org.apache.drill.exec.work.foreman.rm;

import org.apache.drill.exec.proto.UserBitShared.QueryId;

/**
 * Interface which defines a queue implementation for query queues.
 * Implementations can queue locally, queue distributed, or do
 * nothing at all.
 * <p>
 * A queue can report itself as enabled or disabled. When enabled,
 * all queries must obtain a lease prior to starting execution. The
 * lease must be released at the completion of execution.
 */

public interface QueryQueue {

  /**
   * The opaque lease returned once a query is admitted
   * for execution.
   */

  public interface QueueLease { };

  /**
   * Exception thrown if a query exceeds the configured wait time
   * in the query queue.
   */

  @SuppressWarnings("serial")
  public class QueueTimeoutException extends Exception {

    private QueryId queryId;
    private String queueName;
    private int timeoutSecs;

    public QueueTimeoutException(QueryId queryId, String queueName, int timeoutSecs) {
      super( String.format(
          "Unable to acquire queue resources for query within timeout. " +
          "Timeout for %s queue was set at %d seconds.",
          queueName, timeoutSecs));
      this.queryId = queryId;
      this.queueName = queueName;
      this.timeoutSecs = timeoutSecs;
    }

    public QueryId queryId() { return queryId; }
    public String queueName() { return queueName; }
    public int timeoutSecs() { return timeoutSecs; }
  }

  /**
   * Exception thrown for all non-timeout error conditions.
   */

  @SuppressWarnings("serial")
  public class QueryQueueException extends Exception {
    QueryQueueException(String msg, Exception e) {
      super(msg, e);
    }
  }

  /**
   * Determine if the queue is enabled.
   * @return true if the query is enabled, false otherwise.
   */

  boolean enabled();

  /**
   * Queue a query. The method returns only when the query is admitted for
   * execution. As a result, the calling thread may block up to the configured
   * wait time.
   * @param queryId the query ID
   * @param cost the cost of the query used for cost-based queueing
   * @return the query lease which must be passed to {@link #release(QueueLease)}
   * upon query completion
   * @throws QueueTimeoutException if the query times out waiting to be
   * admitted.
   * @throws QueryQueueException for any other error condition.
   */

  QueueLease queue(QueryId queryId, double cost) throws QueueTimeoutException, QueryQueueException;

  /**
   * Release a query lease obtained from {@link #queue(QueryId, double))}.
   * @param lease the lease to be released.
   */

  void release(QueueLease lease);

  void close();
}
