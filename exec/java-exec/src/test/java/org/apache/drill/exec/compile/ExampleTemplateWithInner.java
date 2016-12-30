/**
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
package org.apache.drill.exec.compile;

import org.apache.drill.exec.compile.sig.RuntimeOverridden;
import org.apache.drill.exec.exception.SchemaChangeException;

public abstract class ExampleTemplateWithInner implements ExampleInner{
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ExampleTemplateWithInner.class);

  @Override
  public abstract void doOutside() throws SchemaChangeException;
  public class TheInnerClass{

    @RuntimeOverridden
    public void doInside() throws SchemaChangeException {};

    public void doDouble(){
      DoubleInner di = new DoubleInner();
      di.doDouble();
    }

    public class DoubleInner{
      @RuntimeOverridden
      public void doDouble(){};
    }
  }

  @Override
  public void doInsideOutside() throws SchemaChangeException {
    TheInnerClass inner = new TheInnerClass();
    inner.doInside();
    inner.doDouble();
  }
}
