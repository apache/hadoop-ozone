/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.hdds.scm.container;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ContainerInfoProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.container.metrics.SCMContainerManagerMetrics;
import org.apache.hadoop.hdds.scm.ha.SCMHAManager;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.utils.UniqueId;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.common.statemachine.InvalidStateTransitionException;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: Add javadoc.
 */
public class ContainerManagerImpl implements ContainerManagerV2 {

  /*
   * TODO: Introduce container level locks.
   */

  /**
   *
   */
  private static final Logger LOG = LoggerFactory.getLogger(
      ContainerManagerImpl.class);

  /**
   *
   */
  //Can we move this lock to ContainerStateManager?
  private final ReadWriteLock lock;

  /**
   *
   */
  private final PipelineManager pipelineManager;

  /**
   *
   */
  private final ContainerStateManagerV2 containerStateManager;

  private final SCMHAManager haManager;

  // TODO: Revisit this.
  // Metrics related to operations should be moved to ProtocolServer
  private final SCMContainerManagerMetrics scmContainerManagerMetrics;

  private final int numContainerPerVolume;
  private final Random random = new Random();

  /**
   *
   */
  public ContainerManagerImpl(
      final Configuration conf,
      final SCMHAManager scmHaManager,
      final PipelineManager pipelineManager,
      final Table<ContainerID, ContainerInfo> containerStore)
      throws IOException {
    // Introduce builder for this class?
    this.lock = new ReentrantReadWriteLock();
    this.pipelineManager = pipelineManager;
    this.haManager = scmHaManager;
    this.containerStateManager = ContainerStateManagerImpl.newBuilder()
        .setConfiguration(conf)
        .setPipelineManager(pipelineManager)
        .setRatisServer(scmHaManager.getRatisServer())
        .setContainerStore(containerStore)
        .build();

    this.numContainerPerVolume = conf
        .getInt(ScmConfigKeys.OZONE_SCM_PIPELINE_OWNER_CONTAINER_COUNT,
            ScmConfigKeys.OZONE_SCM_PIPELINE_OWNER_CONTAINER_COUNT_DEFAULT);

    this.scmContainerManagerMetrics = SCMContainerManagerMetrics.create();


  }

  @Override
  public ContainerInfo getContainer(final ContainerID id)
      throws ContainerNotFoundException {
    lock.readLock().lock();
    try {
      return Optional.ofNullable(containerStateManager
          .getContainer(id.getProtobuf()))
          .orElseThrow(() -> new ContainerNotFoundException("ID " + id));
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public List<ContainerInfo> getContainers(final ContainerID startID,
                                           final int count) {
    lock.readLock().lock();
    scmContainerManagerMetrics.incNumListContainersOps();
    try {
      // TODO: Remove the null check, startID should not be null. Fix the unit
      //  test before removing the check.
      final long start = startID == null ? 0 : startID.getId();
      final List<ContainerID> containersIds =
          new ArrayList<>(containerStateManager.getContainerIDs());
      Collections.sort(containersIds);
      scmContainerManagerMetrics.incNumListContainersOps();
      return containersIds.stream()
          .filter(id -> id.getId() > start).limit(count)
          .map(ContainerID::getProtobuf)
          .map(containerStateManager::getContainer)
          .collect(Collectors.toList());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public List<ContainerInfo> getContainers(final LifeCycleState state) {
    lock.readLock().lock();
    try {
      return containerStateManager.getContainerIDs(state).stream()
          .map(ContainerID::getProtobuf)
          .map(containerStateManager::getContainer)
          .filter(Objects::nonNull).collect(Collectors.toList());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public ContainerInfo allocateContainer(final ReplicationType type,
      final ReplicationFactor replicationFactor, final String owner)
      throws IOException {
    lock.writeLock().lock();
    try {
      final List<Pipeline> pipelines = pipelineManager
          .getPipelines(type, replicationFactor, Pipeline.PipelineState.OPEN);

      final Pipeline pipeline;
      if (pipelines.isEmpty()) {
        try {
          pipeline = pipelineManager.createPipeline(type, replicationFactor);
          pipelineManager.waitPipelineReady(pipeline.getId(), 0);
        } catch (IOException e) {
          scmContainerManagerMetrics.incNumFailureCreateContainers();
          throw new IOException("Could not allocate container. Cannot get any" +
              " matching pipeline for Type:" + type + ", Factor:" +
              replicationFactor + ", State:PipelineState.OPEN", e);
        }
      } else {
        pipeline = pipelines.get(random.nextInt(pipelines.size()));
      }
      final ContainerInfo containerInfo = allocateContainer(pipeline, owner);
      if (LOG.isTraceEnabled()) {
        LOG.trace("New container allocated: {}", containerInfo);
      }
      return containerInfo;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private ContainerInfo allocateContainer(final Pipeline pipeline,
                                          final String owner)
      throws IOException {
    // TODO: Replace this with Distributed unique id generator.
    final long uniqueId = UniqueId.next();
    Preconditions.checkState(uniqueId > 0,
        "Cannot allocate container, negative container id" +
            " generated. %s.", uniqueId);
    final ContainerID containerID = ContainerID.valueOf(uniqueId);
    final ContainerInfoProto containerInfo = ContainerInfoProto.newBuilder()
        .setState(LifeCycleState.OPEN)
        .setPipelineID(pipeline.getId().getProtobuf())
        .setUsedBytes(0)
        .setNumberOfKeys(0)
        .setStateEnterTime(Time.now())
        .setOwner(owner)
        .setContainerID(containerID.getId())
        .setDeleteTransactionId(0)
        .setReplicationFactor(pipeline.getFactor())
        .setReplicationType(pipeline.getType())
        .build();
    containerStateManager.addContainer(containerInfo);
    scmContainerManagerMetrics.incNumSuccessfulCreateContainers();
    return containerStateManager.getContainer(containerID.getProtobuf());
  }

  @Override
  public void updateContainerState(final ContainerID id,
                                   final LifeCycleEvent event)
      throws IOException, InvalidStateTransitionException {
    final HddsProtos.ContainerID cid = id.getProtobuf();
    lock.writeLock().lock();
    try {
      if (containerExist(cid)) {
        containerStateManager.updateContainerState(cid, event);
      } else {
        throwContainerNotFoundException(cid);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Set<ContainerReplica> getContainerReplicas(final ContainerID id)
      throws ContainerNotFoundException {
    lock.readLock().lock();
    try {
      return Optional.ofNullable(containerStateManager
          .getContainerReplicas(id.getProtobuf()))
          .orElseThrow(() -> new ContainerNotFoundException("ID " + id));
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void updateContainerReplica(final ContainerID id,
                                     final ContainerReplica replica)
      throws ContainerNotFoundException {
    final HddsProtos.ContainerID cid = id.getProtobuf();
    lock.writeLock().lock();
    try {
      if (containerExist(cid)) {
        containerStateManager.updateContainerReplica(cid, replica);
      } else {
        throwContainerNotFoundException(cid);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void removeContainerReplica(final ContainerID id,
                                     final ContainerReplica replica)
      throws ContainerNotFoundException, ContainerReplicaNotFoundException {
    final HddsProtos.ContainerID cid = id.getProtobuf();
    lock.writeLock().lock();
    try {
      if (containerExist(cid)) {
        containerStateManager.removeContainerReplica(cid, replica);
      } else {
        throwContainerNotFoundException(cid);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void updateDeleteTransactionId(
      final Map<ContainerID, Long> deleteTransactionMap) throws IOException {
    lock.writeLock().lock();
    try {
      containerStateManager.updateDeleteTransactionId(deleteTransactionMap);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public ContainerInfo getMatchingContainer(final long size, final String owner,
      final Pipeline pipeline, final Set<ContainerID> excludedContainerIDs) {
    NavigableSet<ContainerID> containerIDs;
    ContainerInfo containerInfo;
    try {
      synchronized (pipeline.getId()) {
        containerIDs = getContainersForOwner(pipeline, owner);
        if (containerIDs.size() < getOpenContainerCountPerPipeline(pipeline)) {
          allocateContainer(pipeline, owner);
          containerIDs = getContainersForOwner(pipeline, owner);
        }
        containerIDs.removeAll(excludedContainerIDs);
        containerInfo = containerStateManager.getMatchingContainer(
            size, owner, pipeline.getId(), containerIDs);
        if (containerInfo == null) {
          containerInfo = allocateContainer(pipeline, owner);
        }
        return containerInfo;
      }
    } catch (Exception e) {
      LOG.warn("Container allocation failed on pipeline={}", pipeline, e);
      return null;
    }
  }

  private int getOpenContainerCountPerPipeline(Pipeline pipeline) {
    int minContainerCountPerDn = numContainerPerVolume *
        pipelineManager.minHealthyVolumeNum(pipeline);
    int minPipelineCountPerDn = pipelineManager.minPipelineLimit(pipeline);
    return (int) Math.ceil(
        ((double) minContainerCountPerDn / minPipelineCountPerDn));
  }

  /**
   * Returns the container ID's matching with specified owner.
   * @param pipeline
   * @param owner
   * @return NavigableSet<ContainerID>
   */
  private NavigableSet<ContainerID> getContainersForOwner(
      Pipeline pipeline, String owner) throws IOException {
    NavigableSet<ContainerID> containerIDs =
        pipelineManager.getContainersInPipeline(pipeline.getId());
    Iterator<ContainerID> containerIDIterator = containerIDs.iterator();
    while (containerIDIterator.hasNext()) {
      ContainerID cid = containerIDIterator.next();
      try {
        if (!getContainer(cid).getOwner().equals(owner)) {
          containerIDIterator.remove();
        }
      } catch (ContainerNotFoundException e) {
        LOG.error("Could not find container info for container {}", cid, e);
        containerIDIterator.remove();
      }
    }
    return containerIDs;
  }

  @Override
  public void notifyContainerReportProcessing(final boolean isFullReport,
                                              final boolean success) {
    if (isFullReport) {
      if (success) {
        scmContainerManagerMetrics.incNumContainerReportsProcessedSuccessful();
      } else {
        scmContainerManagerMetrics.incNumContainerReportsProcessedFailed();
      }
    } else {
      if (success) {
        scmContainerManagerMetrics.incNumICRReportsProcessedSuccessful();
      } else {
        scmContainerManagerMetrics.incNumICRReportsProcessedFailed();
      }
    }
  }

  @Override
  public void deleteContainer(final ContainerID id)
      throws IOException {
    final HddsProtos.ContainerID cid = id.getProtobuf();
    lock.writeLock().lock();
    try {
      if (containerExist(cid)) {
        containerStateManager.removeContainer(cid);
        scmContainerManagerMetrics.incNumSuccessfulDeleteContainers();
      } else {
        scmContainerManagerMetrics.incNumFailureDeleteContainers();
        throwContainerNotFoundException(cid);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Deprecated
  private void checkIfContainerExist(final HddsProtos.ContainerID id)
      throws ContainerNotFoundException {
    if (!containerStateManager.contains(id)) {
      throw new ContainerNotFoundException("Container with id #" +
          id.getId() + " not found.");
    }
  }

  private boolean containerExist(final HddsProtos.ContainerID id) {
    return containerStateManager.contains(id);
  }

  private void throwContainerNotFoundException(final HddsProtos.ContainerID id)
      throws ContainerNotFoundException {
    throw new ContainerNotFoundException("Container with id #" +
        id.getId() + " not found.");
  }

  @Override
  public void close() throws IOException {
    containerStateManager.close();
  }

}
