/*
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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.hdds.scm.container;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.Preconditions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ContainerInfoProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.protocol.proto.SCMRatisProtocol.RequestType;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.container.states.ContainerState;
import org.apache.hadoop.hdds.scm.container.states.ContainerStateMap;
import org.apache.hadoop.hdds.scm.ha.SCMHAInvocationHandler;
import org.apache.hadoop.hdds.scm.ha.SCMRatisServer;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.pipeline.PipelineNotFoundException;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.Table.KeyValue;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.apache.hadoop.ozone.common.statemachine.InvalidStateTransitionException;
import org.apache.hadoop.ozone.common.statemachine.StateMachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.FINALIZE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.QUASI_CLOSE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.CLOSE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.FORCE_CLOSE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.DELETE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.CLEANUP;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState.OPEN;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState.CLOSING;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState.QUASI_CLOSED;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState.CLOSED;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState.DELETING;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState.DELETED;

/**
 * Default implementation of ContainerStateManager. This implementation
 * holds the Container States in-memory which is backed by a persistent store.
 * The persistent store is always kept in sync with the in-memory state changes.
 *
 * This class is NOT thread safe. All the calls are idempotent.
 */
public final class ContainerStateManagerImpl
    implements ContainerStateManagerV2 {

  /**
   * Logger instance of ContainerStateManagerImpl.
   */
  private static final Logger LOG = LoggerFactory.getLogger(
      ContainerStateManagerImpl.class);

  /**
   * Configured container size.
   */
  private final long containerSize;

  /**
   * In-memory representation of Container States.
   */
  private final ContainerStateMap containers;

  /**
   * Persistent store for Container States.
   */
  private Table<ContainerID, ContainerInfo> containerStore;

  /**
   * PipelineManager instance.
   */
  private final PipelineManager pipelineManager;

  /**
   * Container lifecycle state machine.
   */
  private final StateMachine<LifeCycleState, LifeCycleEvent> stateMachine;

  /**
   * We use the containers in round-robin fashion for operations like block
   * allocation. This map is used for remembering the last used container.
   */
  private final ConcurrentHashMap<ContainerState, ContainerID> lastUsedMap;

  /**
   * constructs ContainerStateManagerImpl instance and loads the containers
   * form the persistent storage.
   *
   * @param conf the Configuration
   * @param pipelineManager the {@link PipelineManager} instance
   * @param containerStore the persistent storage
   * @throws IOException in case of error while loading the containers
   */
  private ContainerStateManagerImpl(final Configuration conf,
      final PipelineManager pipelineManager,
      final Table<ContainerID, ContainerInfo> containerStore)
      throws IOException {
    this.pipelineManager = pipelineManager;
    this.containerStore = containerStore;
    this.stateMachine = newStateMachine();
    this.containerSize = getConfiguredContainerSize(conf);
    this.containers = new ContainerStateMap();
    this.lastUsedMap = new ConcurrentHashMap<>();

    initialize();
  }

  /**
   * Creates and initializes a new Container Lifecycle StateMachine.
   *
   * @return the Container Lifecycle StateMachine
   */
  private StateMachine<LifeCycleState, LifeCycleEvent> newStateMachine() {

    final Set<LifeCycleState> finalStates = new HashSet<>();

    // These are the steady states of a container.
    finalStates.add(CLOSED);
    finalStates.add(DELETED);

    final StateMachine<LifeCycleState, LifeCycleEvent> containerLifecycleSM =
        new StateMachine<>(OPEN, finalStates);

    containerLifecycleSM.addTransition(OPEN, CLOSING, FINALIZE);
    containerLifecycleSM.addTransition(CLOSING, QUASI_CLOSED, QUASI_CLOSE);
    containerLifecycleSM.addTransition(CLOSING, CLOSED, CLOSE);
    containerLifecycleSM.addTransition(QUASI_CLOSED, CLOSED, FORCE_CLOSE);
    containerLifecycleSM.addTransition(CLOSED, DELETING, DELETE);
    containerLifecycleSM.addTransition(DELETING, DELETED, CLEANUP);

    /* The following set of transitions are to make state machine
     * transition idempotent.
     */
    makeStateTransitionIdempotent(containerLifecycleSM, FINALIZE,
        CLOSING, QUASI_CLOSED, CLOSED, DELETING, DELETED);
    makeStateTransitionIdempotent(containerLifecycleSM, QUASI_CLOSE,
        QUASI_CLOSED, CLOSED, DELETING, DELETED);
    makeStateTransitionIdempotent(containerLifecycleSM, CLOSE,
        CLOSED, DELETING, DELETED);
    makeStateTransitionIdempotent(containerLifecycleSM, FORCE_CLOSE,
        CLOSED, DELETING, DELETED);
    makeStateTransitionIdempotent(containerLifecycleSM, DELETE,
        DELETING, DELETED);
    makeStateTransitionIdempotent(containerLifecycleSM, CLEANUP, DELETED);

    return containerLifecycleSM;
  }

  private void makeStateTransitionIdempotent(
      final StateMachine<LifeCycleState, LifeCycleEvent> sm,
      final LifeCycleEvent event, final LifeCycleState... states) {
    for (LifeCycleState state : states) {
      sm.addTransition(state, state, event);
    }
  }

  /**
   * Returns the configured container size.
   *
   * @return the max size of container
   */
  private long getConfiguredContainerSize(final Configuration conf) {
    return (long) conf.getStorageSize(
        ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE,
        ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE_DEFAULT,
        StorageUnit.BYTES);
  }

  /**
   * Loads the containers from container store into memory.
   *
   * @throws IOException in case of error while loading the containers
   */
  private void initialize() throws IOException {
    TableIterator<ContainerID, ? extends KeyValue<ContainerID, ContainerInfo>>
        iterator = containerStore.iterator();

    while (iterator.hasNext()) {
      final ContainerInfo container = iterator.next().getValue();
      Preconditions.checkNotNull(container);
      containers.addContainer(container);
      if (container.getState() == LifeCycleState.OPEN) {
        try {
          pipelineManager.addContainerToPipeline(container.getPipelineID(),
              container.containerID());
        } catch (PipelineNotFoundException ex) {
          LOG.warn("Found container {} which is in OPEN state with " +
                  "pipeline {} that does not exist. Marking container for " +
                  "closing.", container, container.getPipelineID());
          try {
            updateContainerState(container.containerID().getProtobuf(),
                LifeCycleEvent.FINALIZE);
          } catch (InvalidStateTransitionException e) {
            // This cannot happen.
            LOG.warn("Unable to finalize Container {}.", container);
          }
        }
      }
    }
  }

  @Override
  public Set<ContainerID> getContainerIDs() {
    return containers.getAllContainerIDs();
  }

  @Override
  public Set<ContainerID> getContainerIDs(final LifeCycleState state) {
    return containers.getContainerIDsByState(state);
  }

  @Override
  public ContainerInfo getContainer(final HddsProtos.ContainerID id) {
    return containers.getContainerInfo(
        ContainerID.getFromProtobuf(id));
  }

  @Override
  public void addContainer(final ContainerInfoProto containerInfo)
      throws IOException {

    // Change the exception thrown to PipelineNotFound and
    // ClosedPipelineException once ClosedPipelineException is introduced
    // in PipelineManager.

    Preconditions.checkNotNull(containerInfo);
    final ContainerInfo container = ContainerInfo.fromProtobuf(containerInfo);
    final ContainerID containerID = container.containerID();
    final PipelineID pipelineID = container.getPipelineID();

    if (!containers.contains(containerID)) {
      containerStore.put(containerID, container);
      try {
        containers.addContainer(container);
        pipelineManager.addContainerToPipeline(pipelineID, containerID);
      } catch (Exception ex) {
        LOG.warn("Exception {} occurred while add Container {}.",
            ex, containerInfo);
        containers.removeContainer(containerID);
        containerStore.delete(containerID);
        throw ex;
      }
    }
  }

  @Override
  public boolean contains(final HddsProtos.ContainerID id) {
    // TODO: Remove the protobuf conversion after fixing ContainerStateMap.
    return containers.contains(ContainerID.getFromProtobuf(id));
  }

  @Override
  public void updateContainerState(final HddsProtos.ContainerID containerID,
                                   final LifeCycleEvent event)
      throws IOException, InvalidStateTransitionException {
    // TODO: Remove the protobuf conversion after fixing ContainerStateMap.
    final ContainerID id = ContainerID.getFromProtobuf(containerID);
    if (containers.contains(id)) {
      final ContainerInfo info = containers.getContainerInfo(id);
      final LifeCycleState oldState = info.getState();
      final LifeCycleState newState = stateMachine.getNextState(
          info.getState(), event);
      if (newState.getNumber() > oldState.getNumber()) {
        containers.updateState(id, info.getState(), newState);

        // cleanup pipeline if container switch from open to un-open
        if (oldState == OPEN && newState != OPEN) {
          pipelineManager.removeContainerFromPipeline(info.getPipelineID(),
                                                      id);
        }

        // update RocksDB
        info.setState(newState);
        containerStore.put(id, info);
      }
    }
  }


  @Override
  public Set<ContainerReplica> getContainerReplicas(
      final HddsProtos.ContainerID id) {
    return containers.getContainerReplicas(
        ContainerID.getFromProtobuf(id));
  }

  @Override
  public void updateContainerReplica(final HddsProtos.ContainerID id,
                                     final ContainerReplica replica) {
    containers.updateContainerReplica(ContainerID.getFromProtobuf(id),
        replica);
  }

  @Override
  public void removeContainerReplica(final HddsProtos.ContainerID id,
                                     final ContainerReplica replica) {
    containers.removeContainerReplica(ContainerID.getFromProtobuf(id),
        replica);

  }

  void updateDeleteTransactionId(
      final Map<ContainerID, Long> deleteTransactionMap) {
    throw new UnsupportedOperationException("Not yet implemented!");
  }

  ContainerInfo getMatchingContainer(final long size, String owner,
      PipelineID pipelineID, NavigableSet<ContainerID> containerIDs) {
    throw new UnsupportedOperationException("Not yet implemented!");
  }

  NavigableSet<ContainerID> getMatchingContainerIDs(final String owner,
      final ReplicationType type, final ReplicationFactor factor,
      final LifeCycleState state) {
    throw new UnsupportedOperationException("Not yet implemented!");
  }

  @Override
  public void removeContainer(final HddsProtos.ContainerID id)
      throws IOException {
    ContainerID containerID = ContainerID.getFromProtobuf(id);
    containers.removeContainer(containerID);
    containerStore.delete(containerID);
  }

  @Override
  public void close() throws Exception {
    containerStore.close();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builder for ContainerStateManager.
   */
  public static class Builder {
    private Configuration conf;
    private PipelineManager pipelineMgr;
    private SCMRatisServer scmRatisServer;
    private Table<ContainerID, ContainerInfo> table;

    public Builder setConfiguration(final Configuration config) {
      conf = config;
      return this;
    }

    public Builder setPipelineManager(final PipelineManager pipelineManager) {
      pipelineMgr = pipelineManager;
      return this;
    }

    public Builder setRatisServer(final SCMRatisServer ratisServer) {
      scmRatisServer = ratisServer;
      return this;
    }

    public Builder setContainerStore(
        final Table<ContainerID, ContainerInfo> containerStore) {
      table = containerStore;
      return this;
    }

    public ContainerStateManagerV2 build() throws IOException {
      Preconditions.checkNotNull(conf);
      Preconditions.checkNotNull(pipelineMgr);
      Preconditions.checkNotNull(scmRatisServer);
      Preconditions.checkNotNull(table);

      final ContainerStateManagerV2 csm = new ContainerStateManagerImpl(
          conf, pipelineMgr, table);

      final SCMHAInvocationHandler invocationHandler =
          new SCMHAInvocationHandler(RequestType.CONTAINER, csm,
              scmRatisServer);

      return (ContainerStateManagerV2) Proxy.newProxyInstance(
          SCMHAInvocationHandler.class.getClassLoader(),
          new Class<?>[]{ContainerStateManagerV2.class}, invocationHandler);
    }

  }
}
