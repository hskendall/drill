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
package org.apache.drill.yarn.core;

import java.io.IOException;

import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.Records;

/**
 * Abstract description of a remote process launch that describes the many
 * details needed to launch a process on a remote node.
 * <p>
 * Based on <a href="https://github.com/hortonworks/simple-yarn-app">Simple YARN App</a>.
 */

public class AppSpec extends LaunchSpec {

  /**
   * The memory required in the allocated container, in MB.
   */

  public int memoryMb;

  /**
   * The number of YARN "vcores" (roughly equivalent to CPUs)
   * to allocate to the process.
   */

  public int vCores = 1;

  /**
   * The name of the application given to YARN. Appears in the YARN
   * admin UI.
   */

  public String appName;

  /**
   * The YARN queue in which to place the application launch request.
   */

  public String queueName = "default";

  /**
   * Whether to run the AM in unmanaged mode. Leave this
   * false for production code.
   */

  public boolean unmanaged;

  /**
   * Given this generic description of an application, create the detailed
   * YARN application submission context required to launch the
   * application.
   *
   * @param conf the YARN configuration obtained by reading the Hadoop
   *             configuration files
   * @param app  the YARN definition of the client application to be populated
   *             from this generic description
   * @return the completed application launch context for the given
   * application
   * @throws IOException if localized resources are not found in the
   *                     distributed file system (such as HDFS)
   */

  public ApplicationSubmissionContext createAppLaunchContext(YarnConfiguration conf, YarnClientApplication app) throws IOException {
    ContainerLaunchContext amContainer = createLaunchContext(conf);

    // Finally, set-up ApplicationSubmissionContext for the application
    ApplicationSubmissionContext appContext =
            app.getApplicationSubmissionContext();
    appContext.setApplicationName(appName); // application name
    appContext.setAMContainerSpec(amContainer);
    appContext.setResource(getCapability());
    appContext.setQueue(queueName); // queue

    appContext.setUnmanagedAM(unmanaged);

    // TODO: Security tokens

    return appContext;
  }

  public Resource getCapability() {

    // Set up resource type requirements for ApplicationMaster
    Resource capability = Records.newRecord(Resource.class);
    capability.setMemory(memoryMb);
    capability.setVirtualCores(vCores);
    return capability;
  }

}
