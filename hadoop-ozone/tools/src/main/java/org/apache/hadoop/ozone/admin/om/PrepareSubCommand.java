/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.admin.om;

import static org.apache.hadoop.hdds.HddsUtils.getHostName;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.om.protocol.OzoneManagerProtocol;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.PrepareStatusResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.PrepareStatusResponse.PrepareStatus;

import picocli.CommandLine;

/**
 * Handler of ozone admin om finalizeUpgrade command.
 */
@CommandLine.Command(
    name = "prepare",
    description = "Prepares Ozone Manager for upgrade/downgrade, by applying " +
        "all pending transactions, taking a Ratis snapshot at the last " +
        "transaction and purging all logs on each OM instance. The returned " +
        "transaction #ID corresponds to the last transaction in the quorum in" +
        " which the snapshot is taken.",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class
)
public class PrepareSubCommand implements Callable<Void> {

  @CommandLine.ParentCommand
  private OMAdmin parent;

  @CommandLine.Option(
      names = {"-id", "--service-id"},
      description = "Ozone Manager Service ID",
      required = true
  )
  private String omServiceId;

  @CommandLine.Option(
      names = {"-tawt", "--transaction-apply-wait-timeout"},
      description = "Max time in SECONDS to wait for all transactions before" +
          "the prepare request to be applied to the OM DB.",
      defaultValue = "120",
      hidden = true
  )
  private long txnApplyWaitTimeSeconds;

  @CommandLine.Option(
      names = {"-tact", "--transaction-apply-check-interval"},
      description = "Time in SECONDS to wait between successive checks for " +
          "all transactions to be applied to the OM DB.",
      defaultValue = "5",
      hidden = true
  )
  private long txnApplyCheckIntervalSeconds;

  @CommandLine.Option(
      names = {"-pct", "--prepare-check-interval"},
      description = "Time in SECONDS to wait between successive checks for OM" +
          " preparation.",
      defaultValue = "10",
      hidden = true
  )
  private long prepareCheckInterval;

  @CommandLine.Option(
      names = {"-pt", "--prepare-timeout"},
      description = "Max time in SECONDS to wait for all OMs to be prepared",
      defaultValue = "300",
      hidden = true
  )
  private long prepareTimeOut;

  @Override
  public Void call() throws Exception {
    OzoneManagerProtocol client = parent.createOmClient(omServiceId);
    long prepareTxnId = client.prepareOzoneManager(txnApplyWaitTimeSeconds,
        txnApplyCheckIntervalSeconds);
    System.out.println("Ozone Manager Prepare Request successfully returned " +
        "with Transaction Id : [" + prepareTxnId + "].");

    Map<String, Boolean> omPreparedStatusMap = new HashMap<>();
    Set<String> omHosts  = getOmHostsFromConfig();
    omHosts.forEach(h -> omPreparedStatusMap.put(h, false));

    System.out.println();
    System.out.println("Checking individual OM instances for prepare request " +
        "completion...");
    long endTime = System.currentTimeMillis() + (prepareTimeOut * 1000);
    int expectedNumPreparedOms = omPreparedStatusMap.size();
    int currentNumPreparedOms = 0;
    while (System.currentTimeMillis() < endTime &&
        currentNumPreparedOms < expectedNumPreparedOms) {
      for (Map.Entry<String, Boolean> e : omPreparedStatusMap.entrySet()) {
        if (!e.getValue()) {
          String omHost = e.getKey();
          OzoneManagerProtocol singleOmClient =
              parent.createOmClient(omServiceId, omHost, false);
          PrepareStatusResponse response =
              singleOmClient.getOzoneManagerPrepareStatus(prepareTxnId);
          PrepareStatus status = response.getStatus();
          System.out.println("OM : [" + omHost + "], Prepare " +
              "Status : [" + status.name() + "], Current Transaction Id : [" +
              response.getCurrentTxnIndex() + "]");
          if (status.equals(PrepareStatus.PREPARE_DONE)) {
            e.setValue(true);
            currentNumPreparedOms++;
          }
        }
      }
      if (currentNumPreparedOms < expectedNumPreparedOms) {
        System.out.println("Waiting for " + prepareCheckInterval +
            " seconds before retrying...");
        Thread.sleep(prepareCheckInterval * 1000);
      }
    }
    if (currentNumPreparedOms < expectedNumPreparedOms) {
      throw new Exception("OM Preparation failed since all OMs are not " +
          "prepared yet.");
    } else {
      System.out.println();
      System.out.println("OM Preparation successful! ");
      System.out.println("No new write requests will be allowed until " +
          "preparation is cancelled or upgrade/downgrade is done.");
    }

    return null;
  }

  private Set<String> getOmHostsFromConfig() {
    OzoneConfiguration configuration = parent.getParent().getOzoneConf();
    Collection<String> omNodeIds = OmUtils.getOMNodeIds(configuration,
        omServiceId);
    Set<String> omHosts = new HashSet<>();
    for (String nodeId : OmUtils.emptyAsSingletonNull(omNodeIds)) {
      String rpcAddrKey = OmUtils.addKeySuffixes(OZONE_OM_ADDRESS_KEY,
          omServiceId, nodeId);
      String rpcAddrStr = OmUtils.getOmRpcAddress(configuration, rpcAddrKey);
      Optional<String> hostName = getHostName(rpcAddrStr);
      hostName.ifPresent(omHosts::add);
    }
    return omHosts;
  }

}
