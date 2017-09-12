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
package org.apache.drill.exec.physical.rowSet.impl;

import org.apache.drill.exec.vector.ValueVector;

/**
 * Do-nothing vector state for a map column which has no actual vector
 * associated with it.
 */

public class NullVectorState implements VectorState {

  @Override public void allocate(int cardinality) { }
  @Override public int rollOver(int cardinality) { return 0; }
  @Override public void harvestWithLookAhead() { }
  @Override public void startBatchWithLookAhead() { }
  @Override public void reset() { }
  @Override public ValueVector vector() { return null; }

  public static class UnmanagedVectorState extends NullVectorState {
    ValueVector vector;

    public UnmanagedVectorState(ValueVector vector) {
      this.vector = vector;
    }

    @Override
    public ValueVector vector() { return vector; }
  }
}