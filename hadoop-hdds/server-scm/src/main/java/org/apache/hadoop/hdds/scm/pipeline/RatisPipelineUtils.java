/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.hdds.scm.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.collections.comparators.ComparableComparator;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.client.HddsClientUtils;
import org.apache.hadoop.hdds.ratis.RatisHelper;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.retry.RetryPolicy;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for Ratis pipelines. Contains methods to create and destroy
 * ratis pipelines.
 */
public final class RatisPipelineUtils {

  private static final Logger LOG =
      LoggerFactory.getLogger(RatisPipelineUtils.class);

  private RatisPipelineUtils() {
  }
  /**
   * Removes pipeline from SCM. Sends ratis command to destroy pipeline on all
   * the datanodes.
   *
   * @param pipeline        - Pipeline to be destroyed
   * @param ozoneConf       - Ozone configuration
   * @param grpcTlsConfig
   * @throws IOException
   */
  public static void destroyPipeline(Pipeline pipeline, Configuration ozoneConf,
      GrpcTlsConfig grpcTlsConfig) {
    final RaftGroup group = RatisHelper.newRaftGroup(pipeline);
    if (LOG.isDebugEnabled()) {
      LOG.debug("destroying pipeline:{} with {}", pipeline.getId(), group);
    }
    for (DatanodeDetails dn : pipeline.getNodes()) {
      try {
        destroyPipeline(dn, pipeline.getId(), ozoneConf, grpcTlsConfig);
      } catch (IOException e) {
        LOG.warn("Pipeline destroy failed for pipeline={} dn={} exception={}",
            pipeline.getId(), dn, e.getMessage());
      }
    }
  }

  /**
   * Sends ratis command to destroy pipeline on the given datanode.
   *
   * @param dn         - Datanode on which pipeline needs to be destroyed
   * @param pipelineID - ID of pipeline to be destroyed
   * @param ozoneConf  - Ozone configuration
   * @param grpcTlsConfig - grpc tls configuration
   * @throws IOException
   */
  static void destroyPipeline(DatanodeDetails dn, PipelineID pipelineID,
      Configuration ozoneConf, GrpcTlsConfig grpcTlsConfig) throws IOException {
    final String rpcType = ozoneConf
        .get(ScmConfigKeys.DFS_CONTAINER_RATIS_RPC_TYPE_KEY,
            ScmConfigKeys.DFS_CONTAINER_RATIS_RPC_TYPE_DEFAULT);
    final RetryPolicy retryPolicy = RatisHelper.createRetryPolicy(ozoneConf);
    final RaftPeer p = RatisHelper.toRaftPeer(dn);
    final int maxOutstandingRequests =
        HddsClientUtils.getMaxOutstandingRequests(ozoneConf);
    final TimeDuration requestTimeout =
        RatisHelper.getClientRequestTimeout(ozoneConf);
    try(RaftClient client = RatisHelper
        .newRaftClient(SupportedRpcType.valueOfIgnoreCase(rpcType), p,
            retryPolicy, maxOutstandingRequests, grpcTlsConfig,
            requestTimeout)) {
      client.groupRemove(RaftGroupId.valueOf(pipelineID.getId()),
          true, p.getId());
    }
  }

  static int encodeNodeIdsOfFactorThreePipeline(List<DatanodeDetails> nodes) {
    if (nodes.size() != HddsProtos.ReplicationFactor.THREE.getNumber()) {
      return 0;
    }
    List<UUID> nodeIds = nodes.stream()
        .map(DatanodeDetails::getUuid).distinct()
        .collect(Collectors.toList());
    nodeIds.sort(new ComparableComparator());
    // Only for Factor THREE pipeline.
    return new HashCodeBuilder()
        .append(nodeIds.get(0).toString())
        .append(nodeIds.get(1).toString())
        .append(nodeIds.get(2).toString())
        .toHashCode();
  }

  /**
   * Return first existed pipeline which share the same set of datanodes
   * with the input pipeline.
   * @param stateManager PipelineStateManager
   * @param pipeline input pipeline
   * @return first matched pipeline
   */
  static Pipeline checkPipelineContainSameDatanodes(
      PipelineStateManager stateManager, Pipeline pipeline) {
    List<Pipeline> matchedPipelines = stateManager.getPipelines(
        HddsProtos.ReplicationType.RATIS,
        HddsProtos.ReplicationFactor.THREE)
        .stream().filter(p ->
            // For all OPEN or ALLOCATED pipelines
            (p.getPipelineState() == Pipeline.PipelineState.OPEN ||
                p.getPipelineState() == Pipeline.PipelineState.ALLOCATED) &&
                p.getNodeIdsHash() == pipeline.getNodeIdsHash())
        .collect(Collectors.toList());
    if (matchedPipelines.size() == 0) {
      return null;
    } else {
      return matchedPipelines.stream().findFirst().get();
    }
  }
}
