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
package org.apache.drill.exec.vector.accessor;

import java.math.BigDecimal;

import org.joda.time.Duration;

public class AccessorUtilities {

  private AccessorUtilities() { }

  public static void setFromInt(ColumnWriter writer, int value) {
    switch (writer.valueType()) {
    case BYTES:
      writer.setBytes(Integer.toHexString(value).getBytes());
      break;
    case DOUBLE:
      writer.setDouble(value);
      break;
    case INTEGER:
      writer.setInt(value);
      break;
    case LONG:
      writer.setLong(value);
      break;
    case STRING:
      writer.setString(Integer.toString(value));
      break;
    case DECIMAL:
      writer.setDecimal(BigDecimal.valueOf(value));
      break;
    case PERIOD:
      writer.setPeriod(Duration.millis(value).toPeriod());
      break;
    default:
      throw new IllegalStateException("Unknown writer type: " + writer.valueType());
    }
  }

  public static int sv4Batch(int sv4Index) {
    return sv4Index >>> 16;
  }

  public static int sv4Index(int sv4Index) {
    return sv4Index & 0xFFFF;
  }
}
