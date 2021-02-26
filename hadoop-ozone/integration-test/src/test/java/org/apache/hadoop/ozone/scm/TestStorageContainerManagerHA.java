/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.scm;

import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.ha.SCMRatisServerImpl;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.ozone.MiniOzoneHAClusterImpl;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.OzoneKey;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.junit.Rule;

import org.junit.rules.Timeout;

import java.io.IOException;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.client.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.client.ReplicationType.STAND_ALONE;

/**
 * Base class for Ozone Manager HA tests.
 */
public class TestStorageContainerManagerHA {

  private MiniOzoneHAClusterImpl cluster = null;
  private OzoneConfiguration conf;
  private String clusterId;
  private String scmId;
  private String omServiceId;
  private static int numOfOMs = 3;
  private String scmServiceId;
  // TODO: 3 servers will not work until SCM HA configs are considered in
  // SCM HA Ratis server init
  private static int numOfSCMs = 3;


  @Rule
  public Timeout timeout = new Timeout(300_000);

  /**
   * Create a MiniDFSCluster for testing.
   * <p>
   * Ozone is made active by setting OZONE_ENABLED = true
   *
   * @throws IOException
   */
  @Before
  public void init() throws Exception {
    conf = new OzoneConfiguration();
    clusterId = UUID.randomUUID().toString();
    scmId = UUID.randomUUID().toString();
    omServiceId = "om-service-test1";
    scmServiceId = "scm-service-test1";
    cluster = (MiniOzoneHAClusterImpl) MiniOzoneCluster.newHABuilder(conf)
        .setClusterId(clusterId)
        .setScmId(scmId)
        .setOMServiceId(omServiceId)
        .setSCMServiceId(scmServiceId)
        .setNumOfStorageContainerManagers(numOfSCMs)
        .setNumOfOzoneManagers(numOfOMs)
        .build();
    cluster.waitForClusterToBeReady();
  }

  /**
   * Shutdown MiniDFSCluster.
   */
  @After
  public void shutdown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testAllSCMAreRunning() throws Exception {
    int count = 0;
    List<StorageContainerManager> scms = cluster.getStorageContainerManagers();
    Assert.assertEquals(numOfSCMs, scms.size());
    int peerSize = cluster.getStorageContainerManager().getScmHAManager()
        .getRatisServer().getDivision().getGroup().getPeers().size();
    for (StorageContainerManager scm : scms) {
      if (scm.checkLeader()) {
        count++;
      }
      Assert.assertTrue(peerSize == numOfSCMs);
    }
    Assert.assertEquals(1, count);
    count = 0;
    List<OzoneManager> oms = cluster.getOzoneManagersList();
    Assert.assertEquals(numOfOMs, oms.size());
    for (OzoneManager om : oms) {
      if (om.isLeaderReady()) {
        count++;
      }
    }
    Assert.assertEquals(1, count);
    testPutKey();
  }

  public void testPutKey() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    Instant testStartTime = Instant.now();
    ObjectStore store =
        OzoneClientFactory.getRpcClient(cluster.getConf()).getObjectStore();
    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String keyName = UUID.randomUUID().toString();

    OzoneOutputStream out = bucket
        .createKey(keyName, value.getBytes(UTF_8).length, STAND_ALONE, ONE,
            new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();
    OzoneKey key = bucket.getKey(keyName);
    Assert.assertEquals(keyName, key.getName());
    OzoneInputStream is = bucket.readKey(keyName);
    byte[] fileContent = new byte[value.getBytes(UTF_8).length];
    is.read(fileContent);
    Assert.assertEquals(value, new String(fileContent, UTF_8));
    Assert.assertFalse(key.getCreationTime().isBefore(testStartTime));
    Assert.assertFalse(key.getModificationTime().isBefore(testStartTime));
    is.close();
    final OmKeyArgs keyArgs = new OmKeyArgs.Builder().setVolumeName(volumeName)
        .setBucketName(bucketName).setType(HddsProtos.ReplicationType.RATIS)
        .setFactor(HddsProtos.ReplicationFactor.ONE).setKeyName(keyName)
        .setRefreshPipeline(true).build();
    final OmKeyInfo keyInfo = cluster.getOzoneManager().lookupKey(keyArgs);
    final List<OmKeyLocationInfo> keyLocationInfos =
        keyInfo.getKeyLocationVersions().get(0).getBlocksLatestVersionOnly();
    final long containerID = keyLocationInfos.get(0).getContainerID();
    for (int k = 0; k < numOfSCMs; k++) {
      StorageContainerManager scm =
          cluster.getStorageContainerManagers().get(k);
      // flush to DB on each SCM
      ((SCMRatisServerImpl) scm.getScmHAManager().getRatisServer())
          .getStateMachine().takeSnapshot();
      Assert.assertTrue(scm.getContainerManager()
          .containerExist(ContainerID.valueOf(containerID)));
      Assert.assertNotNull(scm.getScmMetadataStore().getContainerTable()
          .get(ContainerID.valueOf(containerID)));
    }
  }
}
