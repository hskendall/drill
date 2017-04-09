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
package org.apache.drill.exec.ops;

import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.testing.ControlsInjector;

/**
 * Defines the set of services used by low-level operator implementations.
 * Avoids having to mock higher-level services not used by low-level
 * items.
 * <p>
 * TODO: Rename or restructure this to merge with a narrowed
 * OperatorContext
 */

public interface OperExecContext extends FragmentExecContext {

  <T extends PhysicalOperator> T getOperatorDefn();

  BufferAllocator getAllocator();

  OperatorStatReceiver getStats();

  ControlsInjector getInjector();
  void injectUnchecked(String desc);
  <T extends Throwable> void injectChecked(String desc, Class<T> exceptionClass)
      throws T;
}
