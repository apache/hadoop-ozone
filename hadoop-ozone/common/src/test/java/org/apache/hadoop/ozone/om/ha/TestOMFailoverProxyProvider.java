/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.om.ha;

import org.junit.Assert;
import org.junit.Test;
import org.junit.Before;

import java.util.StringJoiner;

import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;

import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_NODES_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.
    OZONE_CLIENT_WAIT_BETWEEN_RETRIES_MILLIS_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.
    OZONE_CLIENT_WAIT_BETWEEN_RETRIES_MILLIS_DEFAULT;

/**
 * Tests OMFailoverProxyProvider failover behaviour.
 */
public class TestOMFailoverProxyProvider {
  private final static String OM_SERVICE_ID = "om-service-test1";
  private final static String NODE_ID_BASE_STR = "omNode-";
  private final static String DUMMY_NODE_ADDR = "0.0.0.0:8080";
  private OMFailoverProxyProvider provider;
  private long waitBetweenRetries;

  @Before
  public void init() throws Exception {
    OzoneConfiguration config = new OzoneConfiguration();
    long numNodes = 3;
    waitBetweenRetries = config.getLong(
        OZONE_CLIENT_WAIT_BETWEEN_RETRIES_MILLIS_KEY,
        OZONE_CLIENT_WAIT_BETWEEN_RETRIES_MILLIS_DEFAULT);
    StringJoiner allNodeIds = new StringJoiner(",");
    for (int i = 1; i <= numNodes; i++) {
      String nodeId = NODE_ID_BASE_STR + i;
      config.set(OmUtils.addKeySuffixes(OZONE_OM_ADDRESS_KEY, OM_SERVICE_ID,
          nodeId), DUMMY_NODE_ADDR);
      allNodeIds.add(nodeId);
    }
    config.set(OmUtils.addKeySuffixes(OZONE_OM_NODES_KEY, OM_SERVICE_ID),
        allNodeIds.toString());
    provider = new OMFailoverProxyProvider(config,
        UserGroupInformation.getCurrentUser(), OM_SERVICE_ID);
  }

  /**
   * Tests waitTime when fail over to next node.
   */
  @Test
  public void testWaitTimeWithNextNode() {
    failoverToNextNode(2, 0);
    // After 3 attempts done, wait time should be waitBetweenRetries.
    failoverToNextNode(1, waitBetweenRetries);
    // From 4th Attempt waitTime should reset to 0.
    failoverToNextNode(2, 0);
    // After 2nd round of 3attempts done, wait time should be
    // waitBetweenRetries.
    failoverToNextNode(1, waitBetweenRetries);
  }

  /**
   * 1. Try failover to same node: wiatTime should increment
   * attempts*waitBetweenRetries.
   * 2. Try failover to next node: waitTime should be 0.
   */
  @Test
  public void testWaitTimeWithSameNodeFailover() {
    // Failover attempt 1 to same OM, waitTime should increase.
    failoverToSameNode(4);
    // 2 same node failovers, waitTime should be 0.
    failoverToNextNode(2, 0);
  }

  /**
   * Tests failover to next node and same node.
   */
  @Test
  public void testWaitTimeWithNextNodeAndSameNodeFailover() {
    failoverToNextNode(1, 0);
    // 1 Failover attempt to same OM, waitTime should increase.
    failoverToSameNode(2);
  }

  /**
   * Tests wait time should reset in thr following case:
   * 1. Do a couple same node failover attempts.
   * 2. Next node failover should reset wait time to 0.
   */
  @Test
  public void testWaitTimeResetWhenNextNodeFailoverAfterSameNode() {
    // 2 failover attempts to same OM, waitTime should increase.
    failoverToSameNode(2);
    // Failover to next node, should reset waitTime to 0.
    failoverToNextNode(1, 0);
  }

  /**
   * Tests waitTime reset after same node failover.
   */
  @Test
  public void testWaitTimeResetWhenAllNodeFailoverAndSameNode() {
    // Next node failover wait time should be 0.
    failoverToNextNode(2, 0);
    // Once all numNodes failover done, waitTime should be waitBetweenRetries
    failoverToNextNode(1, waitBetweenRetries);
    // 1 failover attempt to same OM, waitTime should increase.
    failoverToSameNode(2);
    // Next node failover should reset wait time.
    failoverToNextNode(2, 0);
    failoverToNextNode(1, waitBetweenRetries);
  }

  /**
   * Failover to next node and wait time should be same as waitTimeAfter.
   */
  private void failoverToNextNode(int numNextNodeFailoverTimes,
      long waitTimeAfter) {
    for (int attempt = 0; attempt < numNextNodeFailoverTimes; attempt++) {
      provider.performFailoverToNextProxy();
      Assert.assertEquals(waitTimeAfter, provider.getWaitTime());
    }
  }

  /**
   * Failover to same node and wait time will be attempt*waitBetweenRetries.
   */
  private void failoverToSameNode(int numSameNodeFailoverTimes) {
    for (int attempt = 1; attempt <= numSameNodeFailoverTimes; attempt++) {
      provider.performFailoverIfRequired(provider.getCurrentProxyOMNodeId());
      Assert.assertEquals(attempt * waitBetweenRetries,
          provider.getWaitTime());
    }
  }
}
