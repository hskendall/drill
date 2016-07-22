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
package org.apache.drill.yarn.mock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.apache.drill.yarn.appMaster.ClusterController;
import org.apache.drill.yarn.appMaster.Pollable;

/**
 * Implements a "poor man's" command interface which reads commands from a text file. This is a stand-in for the later
 * Client-to-AM interface.
 */

public class MockCommandPollable implements Pollable {
  private ClusterController controller;

  public MockCommandPollable(ClusterController controller) {
    this.controller = controller;
    File file = new File("/tmp/cmd");
    file.delete();
  }

  @Override
  public void tick(long curTime) {
    File file = new File("/tmp/cmd");
    if (!file.exists()) {
      return;
    }
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new FileReader(file));
      String line = reader.readLine();
      if (line != null && !line.isEmpty()) {
        parseCommand(line);
      }
    } catch (Exception e) {
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
        }
      }
      file.delete();
    }
  }

  private void parseCommand(String line) {
    if (line.startsWith("+")) {
      controller.resizeDelta(1);
      return;
    }
    if (line.startsWith("-")) {
      controller.resizeDelta(-1);
      return;
    }
    if (line.equalsIgnoreCase("stop")) {
      controller.shutDown();
      return;
    }
  }
}
