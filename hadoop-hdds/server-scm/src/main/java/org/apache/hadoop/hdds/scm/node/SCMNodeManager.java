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
package org.apache.hadoop.hdds.scm.node;

import javax.management.ObjectName;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

import org.apache.hadoop.hdds.DFSConfigKeysLegacy;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeState;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.NodeReportProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineReportsProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMRegisteredResponseProto.ErrorCode;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMVersionRequestProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.StorageReportProto;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.VersionInfo;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.placement.metrics.SCMNodeMetric;
import org.apache.hadoop.hdds.scm.container.placement.metrics.SCMNodeStat;
import org.apache.hadoop.hdds.scm.ha.SCMHAManager;
import org.apache.hadoop.hdds.scm.net.NetworkTopology;
import org.apache.hadoop.hdds.scm.node.states.NodeAlreadyExistsException;
import org.apache.hadoop.hdds.scm.node.states.NodeNotFoundException;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.server.events.EventPublisher;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.metrics2.util.MBeans;
import org.apache.hadoop.net.CachedDNSToSwitchMapping;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.TableMapping;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.protocol.VersionResponse;
import org.apache.hadoop.ozone.protocol.commands.CommandForDatanode;
import org.apache.hadoop.ozone.protocol.commands.RegisteredCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.hadoop.util.ReflectionUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains information about the Datanodes on SCM side.
 * <p>
 * Heartbeats under SCM is very simple compared to HDFS heartbeatManager.
 * <p>
 * The getNode(byState) functions make copy of node maps and then creates a list
 * based on that. It should be assumed that these get functions always report
 * *stale* information. For example, getting the deadNodeCount followed by
 * getNodes(DEAD) could very well produce totally different count. Also
 * getNodeCount(HEALTHY) + getNodeCount(DEAD) + getNodeCode(STALE), is not
 * guaranteed to add up to the total nodes that we know off. Please treat all
 * get functions in this file as a snap-shot of information that is inconsistent
 * as soon as you read it.
 */
public class SCMNodeManager implements NodeManager {

  private static final Logger LOG =
      LoggerFactory.getLogger(SCMNodeManager.class);

  private final NodeStateManager nodeStateManager;
  private final VersionInfo version;
  private final CommandQueue commandQueue;
  private final SCMNodeMetrics metrics;
  // Node manager MXBean
  private ObjectName nmInfoBean;
  private final SCMStorageConfig scmStorageConfig;
  private final NetworkTopology clusterMap;
  private final DNSToSwitchMapping dnsToSwitchMapping;
  private final boolean useHostname;
  private final ConcurrentHashMap<String, Set<String>> dnsToUuidMap =
      new ConcurrentHashMap<>();
  private final int numPipelinesPerMetadataVolume;
  private final int heavyNodeCriteria;
  private final SCMHAManager scmhaManager;

  /**
   * Constructs SCM machine Manager.
   */
  public SCMNodeManager(OzoneConfiguration conf,
                        SCMStorageConfig scmStorageConfig,
                        EventPublisher eventPublisher,
                        NetworkTopology networkTopology,
                        SCMHAManager scmhaManager) {
    this.nodeStateManager = new NodeStateManager(conf, eventPublisher);
    this.version = VersionInfo.getLatestVersion();
    this.commandQueue = new CommandQueue();
    this.scmStorageConfig = scmStorageConfig;
    LOG.info("Entering startup safe mode.");
    registerMXBean();
    this.metrics = SCMNodeMetrics.create(this);
    this.clusterMap = networkTopology;
    Class<? extends DNSToSwitchMapping> dnsToSwitchMappingClass =
        conf.getClass(
            DFSConfigKeysLegacy.NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY,
            TableMapping.class, DNSToSwitchMapping.class);
    DNSToSwitchMapping newInstance = ReflectionUtils.newInstance(
        dnsToSwitchMappingClass, conf);
    this.dnsToSwitchMapping =
        ((newInstance instanceof CachedDNSToSwitchMapping) ? newInstance
            : new CachedDNSToSwitchMapping(newInstance));
    this.useHostname = conf.getBoolean(
        DFSConfigKeysLegacy.DFS_DATANODE_USE_DN_HOSTNAME,
        DFSConfigKeysLegacy.DFS_DATANODE_USE_DN_HOSTNAME_DEFAULT);
    this.numPipelinesPerMetadataVolume =
        conf.getInt(ScmConfigKeys.OZONE_SCM_PIPELINE_PER_METADATA_VOLUME,
            ScmConfigKeys.OZONE_SCM_PIPELINE_PER_METADATA_VOLUME_DEFAULT);
    String dnLimit = conf.get(ScmConfigKeys.OZONE_DATANODE_PIPELINE_LIMIT);
    this.heavyNodeCriteria = dnLimit == null ? 0 : Integer.parseInt(dnLimit);
    this.scmhaManager = scmhaManager;
  }

  public SCMNodeManager(OzoneConfiguration conf,
                        SCMStorageConfig scmStorageConfig,
                        EventPublisher eventPublisher,
                        NetworkTopology networkTopology) {
    this(conf, scmStorageConfig, eventPublisher, networkTopology, null);
  }

  private void registerMXBean() {
    this.nmInfoBean = MBeans.register("SCMNodeManager",
        "SCMNodeManagerInfo", this);
  }

  private void unregisterMXBean() {
    if (this.nmInfoBean != null) {
      MBeans.unregister(this.nmInfoBean);
      this.nmInfoBean = null;
    }
  }

  /**
   * Returns all datanode that are in the given state. This function works by
   * taking a snapshot of the current collection and then returning the list
   * from that collection. This means that real map might have changed by the
   * time we return this list.
   *
   * @return List of Datanodes that are known to SCM in the requested state.
   */
  @Override
  public List<DatanodeDetails> getNodes(NodeState nodestate) {
    return nodeStateManager.getNodes(nodestate).stream()
        .map(node -> (DatanodeDetails) node).collect(Collectors.toList());
  }

  /**
   * Returns all datanodes that are known to SCM.
   *
   * @return List of DatanodeDetails
   */
  @Override
  public List<DatanodeDetails> getAllNodes() {
    return nodeStateManager.getAllNodes().stream()
        .map(node -> (DatanodeDetails) node).collect(Collectors.toList());
  }

  /**
   * Returns the Number of Datanodes by State they are in.
   *
   * @return count
   */
  @Override
  public int getNodeCount(NodeState nodestate) {
    return nodeStateManager.getNodeCount(nodestate);
  }

  /**
   * Returns the node state of a specific node.
   *
   * @param datanodeDetails Datanode Details
   * @return Healthy/Stale/Dead/Unknown.
   */
  @Override
  public NodeState getNodeState(DatanodeDetails datanodeDetails) {
    try {
      return nodeStateManager.getNodeState(datanodeDetails);
    } catch (NodeNotFoundException e) {
      // TODO: should we throw NodeNotFoundException?
      return null;
    }
  }

  /**
   * Closes this stream and releases any system resources associated with it. If
   * the stream is already closed then invoking this method has no effect.
   *
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void close() throws IOException {
    unregisterMXBean();
    metrics.unRegister();
    nodeStateManager.close();
  }

  /**
   * Gets the version info from SCM.
   *
   * @param versionRequest - version Request.
   * @return - returns SCM version info and other required information needed by
   * datanode.
   */
  @Override
  public VersionResponse getVersion(SCMVersionRequestProto versionRequest) {
    return VersionResponse.newBuilder()
        .setVersion(this.version.getVersion())
        .addValue(OzoneConsts.SCM_ID,
            this.scmStorageConfig.getScmId())
        .addValue(OzoneConsts.CLUSTER_ID, this.scmStorageConfig.getClusterID())
        .build();
  }

  /**
   * Register the node if the node finds that it is not registered with any
   * SCM.
   *
   * @param datanodeDetails - Send datanodeDetails with Node info.
   *                        This function generates and assigns new datanode ID
   *                        for the datanode. This allows SCM to be run
   *                        independent
   *                        of Namenode if required.
   * @param nodeReport      NodeReport.
   * @return SCMRegisteredResponseProto
   */
  @Override
  public RegisteredCommand register(
      DatanodeDetails datanodeDetails, NodeReportProto nodeReport,
      PipelineReportsProto pipelineReportsProto) {

    if (!isNodeRegistered(datanodeDetails)) {
      InetAddress dnAddress = Server.getRemoteIp();
      if (dnAddress != null) {
        // Mostly called inside an RPC, update ip and peer hostname
        datanodeDetails.setHostName(dnAddress.getHostName());
        datanodeDetails.setIpAddress(dnAddress.getHostAddress());
      }
      try {
        String dnsName;
        String networkLocation;
        datanodeDetails.setNetworkName(datanodeDetails.getUuidString());
        if (useHostname) {
          dnsName = datanodeDetails.getHostName();
        } else {
          dnsName = datanodeDetails.getIpAddress();
        }
        networkLocation = nodeResolve(dnsName);
        if (networkLocation != null) {
          datanodeDetails.setNetworkLocation(networkLocation);
        }

        clusterMap.add(datanodeDetails);
        nodeStateManager.addNode(datanodeDetails);
        // Check that datanode in nodeStateManager has topology parent set
        DatanodeDetails dn = nodeStateManager.getNode(datanodeDetails);
        Preconditions.checkState(dn.getParent() != null);
        addEntryTodnsToUuidMap(dnsName, datanodeDetails.getUuidString());
        // Updating Node Report, as registration is successful
        processNodeReport(datanodeDetails, nodeReport);
        LOG.info("Registered Data node : {}", datanodeDetails);
      } catch (NodeAlreadyExistsException e) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("Datanode is already registered. Datanode: {}",
              datanodeDetails.toString());
        }
      } catch (NodeNotFoundException e) {
        LOG.error("Cannot find datanode {} from nodeStateManager",
            datanodeDetails.toString());
      }
    }

    return RegisteredCommand.newBuilder().setErrorCode(ErrorCode.success)
        .setDatanode(datanodeDetails)
        .setClusterID(this.scmStorageConfig.getClusterID())
        .build();
  }

  /**
   * Add an entry to the dnsToUuidMap, which maps hostname / IP to the DNs
   * running on that host. As each address can have many DNs running on it,
   * this is a one to many mapping.
   *
   * @param dnsName String representing the hostname or IP of the node
   * @param uuid    String representing the UUID of the registered node.
   */
  @SuppressFBWarnings(value = "AT_OPERATION_SEQUENCE_ON_CONCURRENT_ABSTRACTION",
      justification = "The method is synchronized and this is the only place " +
          "dnsToUuidMap is modified")
  private synchronized void addEntryTodnsToUuidMap(
      String dnsName, String uuid) {
    Set<String> dnList = dnsToUuidMap.get(dnsName);
    if (dnList == null) {
      dnList = ConcurrentHashMap.newKeySet();
      dnsToUuidMap.put(dnsName, dnList);
    }
    dnList.add(uuid);
  }

  /**
   * Send heartbeat to indicate the datanode is alive and doing well.
   *
   * @param datanodeDetails - DatanodeDetailsProto.
   * @return SCMheartbeat response.
   */
  @Override
  public List<SCMCommand> processHeartbeat(DatanodeDetails datanodeDetails) {
    Preconditions.checkNotNull(datanodeDetails, "Heartbeat is missing " +
        "DatanodeDetails.");
    try {
      nodeStateManager.updateLastHeartbeatTime(datanodeDetails);
      metrics.incNumHBProcessed();
    } catch (NodeNotFoundException e) {
      metrics.incNumHBProcessingFailed();
      LOG.error("SCM trying to process heartbeat from an " +
          "unregistered node {}. Ignoring the heartbeat.", datanodeDetails);
    }
    return commandQueue.getCommand(datanodeDetails.getUuid());
  }

  @Override
  public Boolean isNodeRegistered(DatanodeDetails datanodeDetails) {
    try {
      nodeStateManager.getNode(datanodeDetails);
      return true;
    } catch (NodeNotFoundException e) {
      return false;
    }
  }

  /**
   * Process node report.
   *
   * @param datanodeDetails
   * @param nodeReport
   */
  @Override
  public void processNodeReport(DatanodeDetails datanodeDetails,
      NodeReportProto nodeReport) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing node report from [datanode={}]",
          datanodeDetails.getHostName());
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("HB is received from [datanode={}]: <json>{}</json>",
          datanodeDetails.getHostName(),
          nodeReport.toString().replaceAll("\n", "\\\\n"));
    }
    try {
      DatanodeInfo datanodeInfo = nodeStateManager.getNode(datanodeDetails);
      if (nodeReport != null) {
        datanodeInfo.updateStorageReports(nodeReport.getStorageReportList());
        datanodeInfo.updateMetaDataStorageReports(nodeReport.
            getMetadataStorageReportList());
        metrics.incNumNodeReportProcessed();
      }
    } catch (NodeNotFoundException e) {
      metrics.incNumNodeReportProcessingFailed();
      LOG.warn("Got node report from unregistered datanode {}",
          datanodeDetails);
    }
  }

  /**
   * Returns the aggregated node stats.
   *
   * @return the aggregated node stats.
   */
  @Override
  public SCMNodeStat getStats() {
    long capacity = 0L;
    long used = 0L;
    long remaining = 0L;

    for (SCMNodeStat stat : getNodeStats().values()) {
      capacity += stat.getCapacity().get();
      used += stat.getScmUsed().get();
      remaining += stat.getRemaining().get();
    }
    return new SCMNodeStat(capacity, used, remaining);
  }

  /**
   * Return a map of node stats.
   *
   * @return a map of individual node stats (live/stale but not dead).
   */
  @Override
  public Map<DatanodeDetails, SCMNodeStat> getNodeStats() {

    final Map<DatanodeDetails, SCMNodeStat> nodeStats = new HashMap<>();

    final List<DatanodeInfo> healthyNodes = nodeStateManager
        .getNodes(NodeState.HEALTHY);
    final List<DatanodeInfo> staleNodes = nodeStateManager
        .getNodes(NodeState.STALE);
    final List<DatanodeInfo> datanodes = new ArrayList<>(healthyNodes);
    datanodes.addAll(staleNodes);

    for (DatanodeInfo dnInfo : datanodes) {
      SCMNodeStat nodeStat = getNodeStatInternal(dnInfo);
      if (nodeStat != null) {
        nodeStats.put(dnInfo, nodeStat);
      }
    }
    return nodeStats;
  }

  /**
   * Return the node stat of the specified datanode.
   *
   * @param datanodeDetails - datanode ID.
   * @return node stat if it is live/stale, null if it is decommissioned or
   * doesn't exist.
   */
  @Override
  public SCMNodeMetric getNodeStat(DatanodeDetails datanodeDetails) {
    final SCMNodeStat nodeStat = getNodeStatInternal(datanodeDetails);
    return nodeStat != null ? new SCMNodeMetric(nodeStat) : null;
  }

  private SCMNodeStat getNodeStatInternal(DatanodeDetails datanodeDetails) {
    try {
      long capacity = 0L;
      long used = 0L;
      long remaining = 0L;

      final DatanodeInfo datanodeInfo = nodeStateManager
          .getNode(datanodeDetails);
      final List<StorageReportProto> storageReportProtos = datanodeInfo
          .getStorageReports();
      for (StorageReportProto reportProto : storageReportProtos) {
        capacity += reportProto.getCapacity();
        used += reportProto.getScmUsed();
        remaining += reportProto.getRemaining();
      }
      return new SCMNodeStat(capacity, used, remaining);
    } catch (NodeNotFoundException e) {
      LOG.warn("Cannot generate NodeStat, datanode {} not found.",
          datanodeDetails.getUuid());
      return null;
    }
  }

  @Override
  public Map<String, Integer> getNodeCount() {
    Map<String, Integer> nodeCountMap = new HashMap<String, Integer>();
    for (NodeState state : NodeState.values()) {
      nodeCountMap.put(state.toString(), getNodeCount(state));
    }
    return nodeCountMap;
  }

  // We should introduce DISK, SSD, etc., notion in
  // SCMNodeStat and try to use it.
  @Override
  public Map<String, Long> getNodeInfo() {
    long diskCapacity = 0L;
    long diskUsed = 0L;
    long diskRemaning = 0L;

    long ssdCapacity = 0L;
    long ssdUsed = 0L;
    long ssdRemaining = 0L;

    List<DatanodeInfo> healthyNodes = nodeStateManager
        .getNodes(NodeState.HEALTHY);
    List<DatanodeInfo> staleNodes = nodeStateManager
        .getNodes(NodeState.STALE);

    List<DatanodeInfo> datanodes = new ArrayList<>(healthyNodes);
    datanodes.addAll(staleNodes);

    for (DatanodeInfo dnInfo : datanodes) {
      List<StorageReportProto> storageReportProtos = dnInfo.getStorageReports();
      for (StorageReportProto reportProto : storageReportProtos) {
        if (reportProto.getStorageType() ==
            StorageContainerDatanodeProtocolProtos.StorageTypeProto.DISK) {
          diskCapacity += reportProto.getCapacity();
          diskRemaning += reportProto.getRemaining();
          diskUsed += reportProto.getScmUsed();
        } else if (reportProto.getStorageType() ==
            StorageContainerDatanodeProtocolProtos.StorageTypeProto.SSD) {
          ssdCapacity += reportProto.getCapacity();
          ssdRemaining += reportProto.getRemaining();
          ssdUsed += reportProto.getScmUsed();
        }
      }
    }

    Map<String, Long> nodeInfo = new HashMap<>();
    nodeInfo.put("DISKCapacity", diskCapacity);
    nodeInfo.put("DISKUsed", diskUsed);
    nodeInfo.put("DISKRemaining", diskRemaning);

    nodeInfo.put("SSDCapacity", ssdCapacity);
    nodeInfo.put("SSDUsed", ssdUsed);
    nodeInfo.put("SSDRemaining", ssdRemaining);
    return nodeInfo;
  }

  /**
   * Returns the min of no healthy volumes reported out of the set
   * of datanodes constituting the pipeline.
   */
  @Override
  public int minHealthyVolumeNum(List<DatanodeDetails> dnList) {
    List<Integer> volumeCountList = new ArrayList<>(dnList.size());
    for (DatanodeDetails dn : dnList) {
      try {
        volumeCountList.add(nodeStateManager.getNode(dn).
                getHealthyVolumeCount());
      } catch (NodeNotFoundException e) {
        LOG.warn("Cannot generate NodeStat, datanode {} not found.",
                dn.getUuid());
      }
    }
    Preconditions.checkArgument(!volumeCountList.isEmpty());
    return Collections.min(volumeCountList);
  }

  /**
   * Returns the pipeline limit for the datanode.
   * if the datanode pipeline limit is set, consider that as the max
   * pipeline limit.
   * In case, the pipeline limit is not set, the max pipeline limit
   * will be based on the no of raft log volume reported and provided
   * that it has atleast one healthy data volume.
   */
  @Override
  public int pipelineLimit(DatanodeDetails dn) {
    try {
      if (heavyNodeCriteria > 0) {
        return heavyNodeCriteria;
      } else if (nodeStateManager.getNode(dn).getHealthyVolumeCount() > 0) {
        return numPipelinesPerMetadataVolume *
            nodeStateManager.getNode(dn).getMetaDataVolumeCount();
      }
    } catch (NodeNotFoundException e) {
      LOG.warn("Cannot generate NodeStat, datanode {} not found.",
          dn.getUuid());
    }
    return 0;
  }

  /**
   * Returns the pipeline limit for set of datanodes.
   */
  @Override
  public int minPipelineLimit(List<DatanodeDetails> dnList) {
    List<Integer> pipelineCountList = new ArrayList<>(dnList.size());
    for (DatanodeDetails dn : dnList) {
      pipelineCountList.add(pipelineLimit(dn));
    }
    Preconditions.checkArgument(!pipelineCountList.isEmpty());
    return Collections.min(pipelineCountList);
  }

  /**
   * Get set of pipelines a datanode is part of.
   *
   * @param datanodeDetails - datanodeID
   * @return Set of PipelineID
   */
  @Override
  public Set<PipelineID> getPipelines(DatanodeDetails datanodeDetails) {
    return nodeStateManager.getPipelineByDnID(datanodeDetails.getUuid());
  }

  /**
   * Get the count of pipelines a datanodes is associated with.
   * @param datanodeDetails DatanodeDetails
   * @return The number of pipelines
   */
  @Override
  public int getPipelinesCount(DatanodeDetails datanodeDetails) {
    return nodeStateManager.getPipelinesCount(datanodeDetails);
  }

  /**
   * Add pipeline information in the NodeManager.
   *
   * @param pipeline - Pipeline to be added
   */
  @Override
  public void addPipeline(Pipeline pipeline) {
    nodeStateManager.addPipeline(pipeline);
  }

  /**
   * Remove a pipeline information from the NodeManager.
   *
   * @param pipeline - Pipeline to be removed
   */
  @Override
  public void removePipeline(Pipeline pipeline) {
    nodeStateManager.removePipeline(pipeline);
  }

  @Override
  public void addContainer(final DatanodeDetails datanodeDetails,
      final ContainerID containerId)
      throws NodeNotFoundException {
    nodeStateManager.addContainer(datanodeDetails.getUuid(), containerId);
  }

  /**
   * Update set of containers available on a datanode.
   *
   * @param datanodeDetails - DatanodeID
   * @param containerIds    - Set of containerIDs
   * @throws NodeNotFoundException - if datanode is not known. For new datanode
   *                               use addDatanodeInContainerMap call.
   */
  @Override
  public void setContainers(DatanodeDetails datanodeDetails,
      Set<ContainerID> containerIds) throws NodeNotFoundException {
    nodeStateManager.setContainers(datanodeDetails.getUuid(),
        containerIds);
  }

  /**
   * Return set of containerIDs available on a datanode.
   *
   * @param datanodeDetails - DatanodeID
   * @return - set of containerIDs
   */
  @Override
  public Set<ContainerID> getContainers(DatanodeDetails datanodeDetails)
      throws NodeNotFoundException {
    return nodeStateManager.getContainers(datanodeDetails.getUuid());
  }

  // TODO:
  // Since datanode commands are added through event queue, onMessage method
  // should take care of adding commands to command queue.
  // Refactor and remove all the usage of this method and delete this method.
  /**
   * Only leader SCM can send SCMCommand to datanode, and needs record its
   * term in the command so that datanode can distinguish commands from stale
   * leader SCM by comparing term.
   *
   * There are 7 SCMCommands:
   *    ReregisterCommand
   *    ClosePipelineCommand
   *    CreatePipelineCommand
   *    CloseContainerCommand
   *    ReplicateContainerCommand
   *    DeleteContainerCommand
   *    DeleteBlocksCommand
   *
   * They are sent by:
   *    NodeManager
   *    PipelineManager
   *    ContainerManager
   *    BlockManager
   *    ReplicationManager and etc.
   *
   * Ideally term of SCMCommand should be queried from SCMHAManager::isLeader()
   * before these managers decide to take an action.
   *
   * However till now only several of these managers have been integrated
   * with HA, thus need NodeManager, as EventHandler for DATANODE_COMMAND,
   * to play as a safe guard when receiving a SCMCommand:
   *
   * - If receive a SCMCommand when underling RaftServer is not leader,
   *   drop the command.
   *
   * - If receive a SCMCommand when underling RaftServer is leader, meanwhile
   *   term of SCMCommand is 0 (which is the default value of term and will
   *   not be used under HA mode), log a warning and help set the term.
   *   Notes that, term queried before putting into command queue may be not
   *   accurate, since raft term may bump when managers are taking actions.
   */
  @Override
  public void addDatanodeCommand(UUID dnId, SCMCommand command) {
    if (scmhaManager != null && command.getTerm() == 0) {
      Optional<Long> termOpt = scmhaManager.isLeader();

      if (!termOpt.isPresent()) {
        LOG.warn("Not leader, drop SCMCommand {}.", command);
        return;
      }

      LOG.warn("Help set term {} for SCMCommand {}. It is not an accurate " +
          "way to set term of SCMCommand.", termOpt.get(), command);
      command.setTerm(termOpt.get());
    }
    this.commandQueue.addCommand(dnId, command);
  }

  /**
   * This method is called by EventQueue whenever someone adds a new
   * DATANODE_COMMAND to the Queue.
   *
   * @param commandForDatanode DatanodeCommand
   * @param ignored            publisher
   */
  @Override
  public void onMessage(CommandForDatanode commandForDatanode,
      EventPublisher ignored) {
    addDatanodeCommand(commandForDatanode.getDatanodeId(),
        commandForDatanode.getCommand());
  }

  @Override
  public List<SCMCommand> getCommandQueue(UUID dnID) {
    return commandQueue.getCommand(dnID);
  }

  /**
   * Given datanode uuid, returns the DatanodeDetails for the node.
   *
   * @param uuid node host address
   * @return the given datanode, or null if not found
   */
  @Override
  public DatanodeDetails getNodeByUuid(String uuid) {
    if (Strings.isNullOrEmpty(uuid)) {
      LOG.warn("uuid is null");
      return null;
    }
    DatanodeDetails temp = DatanodeDetails.newBuilder()
        .setUuid(UUID.fromString(uuid)).build();
    try {
      return nodeStateManager.getNode(temp);
    } catch (NodeNotFoundException e) {
      LOG.warn("Cannot find node for uuid {}", uuid);
      return null;
    }
  }

  /**
   * Given datanode address(Ipaddress or hostname), return a list of
   * DatanodeDetails for the datanodes registered on that address.
   *
   * @param address datanode address
   * @return the given datanode, or empty list if none found
   */
  @Override
  public List<DatanodeDetails> getNodesByAddress(String address) {
    List<DatanodeDetails> results = new LinkedList<>();
    if (Strings.isNullOrEmpty(address)) {
      LOG.warn("address is null");
      return results;
    }
    Set<String> uuids = dnsToUuidMap.get(address);
    if (uuids == null) {
      LOG.warn("Cannot find node for address {}", address);
      return results;
    }

    for (String uuid : uuids) {
      DatanodeDetails temp = DatanodeDetails.newBuilder()
          .setUuid(UUID.fromString(uuid)).build();
      try {
        results.add(nodeStateManager.getNode(temp));
      } catch (NodeNotFoundException e) {
        LOG.warn("Cannot find node for uuid {}", uuid);
      }
    }
    return results;
  }

  /**
   * Get cluster map as in network topology for this node manager.
   * @return cluster map
   */
  @Override
  public NetworkTopology getClusterNetworkTopologyMap() {
    return clusterMap;
  }

  private String nodeResolve(String hostname) {
    List<String> hosts = new ArrayList<>(1);
    hosts.add(hostname);
    List<String> resolvedHosts = dnsToSwitchMapping.resolve(hosts);
    if (resolvedHosts != null && !resolvedHosts.isEmpty()) {
      String location = resolvedHosts.get(0);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Resolve datanode {} return location {}", hostname, location);
      }
      return location;
    } else {
      LOG.error("Node {} Resolution failed. Please make sure that DNS table " +
          "mapping or configured mapping is functional.", hostname);
      return null;
    }
  }

  /**
   * Test utility to stop heartbeat check process.
   *
   * @return ScheduledFuture of next scheduled check that got cancelled.
   */
  @VisibleForTesting
  ScheduledFuture pauseHealthCheck() {
    return nodeStateManager.pause();
  }

  /**
   * Test utility to resume the paused heartbeat check process.
   *
   * @return ScheduledFuture of the next scheduled check
   */
  @VisibleForTesting
  ScheduledFuture unpauseHealthCheck() {
    return nodeStateManager.unpause();
  }

  /**
   * Test utility to get the count of skipped heartbeat check iterations.
   *
   * @return count of skipped heartbeat check iterations
   */
  @VisibleForTesting
  long getSkippedHealthChecks() {
    return nodeStateManager.getSkippedHealthChecks();
  }
}
