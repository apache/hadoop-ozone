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

package org.apache.hadoop.ozone.om.ratis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ServiceException;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OMRatisHelper;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.ozone.protocolPB.OzoneManagerRequestHandler;
import org.apache.hadoop.ozone.protocolPB.RequestHandler;
import org.apache.hadoop.util.concurrent.HadoopExecutors;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.ExitUtils;
import org.apache.ratis.util.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status.INTERNAL_ERROR;
import static org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status.METADATA_ERROR;

/**
 * The OM StateMachine is the state machine for OM Ratis server. It is
 * responsible for applying ratis committed transactions to
 * {@link OzoneManager}.
 */
public class OzoneManagerStateMachine extends BaseStateMachine {

  static final Logger LOG =
      LoggerFactory.getLogger(OzoneManagerStateMachine.class);
  private final SimpleStateMachineStorage storage =
      new SimpleStateMachineStorage();
  private final OzoneManagerRatisServer omRatisServer;
  private final OzoneManager ozoneManager;
  private RequestHandler handler;
  private RaftGroupId raftGroupId;
  private OzoneManagerDoubleBuffer ozoneManagerDoubleBuffer;
  private final OMRatisSnapshotInfo snapshotInfo;
  private final ExecutorService executorService;
  private final ExecutorService installSnapshotExecutor;

  private ConcurrentMap<Long, Long> applyTransactionMap =
      new ConcurrentSkipListMap<>();


  public OzoneManagerStateMachine(OzoneManagerRatisServer ratisServer) {
    this.omRatisServer = ratisServer;
    this.ozoneManager = omRatisServer.getOzoneManager();

    this.snapshotInfo = ozoneManager.getSnapshotInfo();
    updateLastAppliedIndexWithSnaphsotIndex();

    this.ozoneManagerDoubleBuffer =
        new OzoneManagerDoubleBuffer(ozoneManager.getMetadataManager(),
            this::updateLastAppliedIndex);

    this.handler = new OzoneManagerRequestHandler(ozoneManager,
        ozoneManagerDoubleBuffer);

    ThreadFactory build = new ThreadFactoryBuilder().setDaemon(true)
        .setNameFormat("OM StateMachine ApplyTransaction Thread - %d").build();
    this.executorService = HadoopExecutors.newSingleThreadExecutor(build);
    this.installSnapshotExecutor = HadoopExecutors.newSingleThreadExecutor();
  }

  /**
   * Initializes the State Machine with the given server, group and storage.
   */
  @Override
  public void initialize(RaftServer server, RaftGroupId id,
      RaftStorage raftStorage) throws IOException {
    lifeCycle.startAndTransition(() -> {
      super.initialize(server, id, raftStorage);
      this.raftGroupId = id;
      storage.init(raftStorage);
    });
  }

  @Override
  public SnapshotInfo getLatestSnapshot() {
    return snapshotInfo;
  }

  /**
   * Called to notify state machine about indexes which are processed
   * internally by Raft Server, this currently happens when conf entries are
   * processed in raft Server. This keep state machine to keep a track of index
   * updates.
   * @param currentTerm term of the current log entry
   * @param index index which is being updated
   */
  @Override
  public void notifyIndexUpdate(long currentTerm, long index) {
    // SnapshotInfo should be updated when the term changes.
    // The index here refers to the log entry index and the index in
    // SnapshotInfo represents the snapshotIndex i.e. the index of the last
    // transaction included in the snapshot. Hence, snaphsotInfo#index is not
    // updated here.
    applyTransactionMap.put(index, currentTerm);
    // We need to call updateLastApplied here because now in ratis when a
    // node becomes leader, it is checking stateMachineIndex >=
    // placeHolderIndex (when a node becomes leader, it writes a conf entry
    // with some information like its peers and termIndex). So, calling
    // updateLastApplied updates lastAppliedTermIndex.
    updateLastAppliedIndex(index);
    snapshotInfo.updateTerm(currentTerm);
  }

  /**
   * Validate/pre-process the incoming update request in the state machine.
   * @return the content to be written to the log entry. Null means the request
   * should be rejected.
   * @throws IOException thrown by the state machine while validating
   */
  @Override
  public TransactionContext startTransaction(
      RaftClientRequest raftClientRequest) throws IOException {
    ByteString messageContent = raftClientRequest.getMessage().getContent();
    OMRequest omRequest = OMRatisHelper.convertByteStringToOMRequest(
        messageContent);

    Preconditions.checkArgument(raftClientRequest.getRaftGroupId().equals(
        raftGroupId));
    try {
      handler.validateRequest(omRequest);
    } catch (IOException ioe) {
      TransactionContext ctxt = TransactionContext.newBuilder()
          .setClientRequest(raftClientRequest)
          .setStateMachine(this)
          .setServerRole(RaftProtos.RaftPeerRole.LEADER)
          .build();
      ctxt.setException(ioe);
      return ctxt;
    }
    return handleStartTransactionRequests(raftClientRequest, omRequest);
  }

  /*
   * Apply a committed log entry to the state machine.
   */
  @Override
  public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
    try {
      OMRequest request = OMRatisHelper.convertByteStringToOMRequest(
          trx.getStateMachineLogEntry().getLogData());
      long trxLogIndex = trx.getLogEntry().getIndex();
      // In the current approach we have one single global thread executor.
      // with single thread. Right now this is being done for correctness, as
      // applyTransaction will be run on multiple OM's we want to execute the
      // transactions in the same order on all OM's, otherwise there is a
      // chance that OM replica's can be out of sync.
      // TODO: In this way we are making all applyTransactions in
      // OM serial order. Revisit this in future to use multiple executors for
      // volume/bucket.

      // Reason for not immediately implementing executor per volume is, if
      // one executor operations are slow, we cannot update the
      // lastAppliedIndex in OzoneManager StateMachine, even if other
      // executor has completed the transactions with id more.

      // We have 300 transactions, And for each volume we have transactions
      // of 150. Volume1 transactions 0 - 149 and Volume2 transactions 150 -
      // 299.
      // Example: Executor1 - Volume1 - 100 (current completed transaction)
      // Example: Executor2 - Volume2 - 299 (current completed transaction)

      // Now we have applied transactions of 0 - 100 and 149 - 299. We
      // cannot update lastAppliedIndex to 299. We need to update it to 100,
      // since 101 - 149 are not applied. When OM restarts it will
      // applyTransactions from lastAppliedIndex.
      // We can update the lastAppliedIndex to 100, and update it to 299,
      // only after completing 101 - 149. In initial stage, we are starting
      // with single global executor. Will revisit this when needed.

      // Add the term index and transaction log index to applyTransaction map
      // . This map will be used to update lastAppliedIndex.

      CompletableFuture<Message> ratisFuture =
          new CompletableFuture<>();

      applyTransactionMap.put(trxLogIndex, trx.getLogEntry().getTerm());
      CompletableFuture<OMResponse> future = CompletableFuture.supplyAsync(
          () -> runCommand(request, trxLogIndex), executorService);
      future.thenApply(omResponse -> {
        if(!omResponse.getSuccess()) {
          // When INTERNAL_ERROR or METADATA_ERROR it is considered as
          // critical error and terminate the OM. Considering INTERNAL_ERROR
          // also for now because INTERNAL_ERROR is thrown for any error
          // which is not type OMException.

          // Not done future with completeExceptionally because if we do
          // that OM will still continue applying transaction until next
          // snapshot. So in OM case if a transaction failed with un
          // recoverable error and if we wait till snapshot to terminate
          // OM, then if some client requested the read transaction of the
          // failed request, there is a chance we shall give wrong result.
          // So, to avoid these kind of issue, we should terminate OM here.
          if (omResponse.getStatus() == INTERNAL_ERROR) {
            terminate(omResponse, OMException.ResultCodes.INTERNAL_ERROR);
          } else if (omResponse.getStatus() == METADATA_ERROR) {
            terminate(omResponse, OMException.ResultCodes.METADATA_ERROR);
          } else {
            // For all other errors which are not critical, we can complete
            // future normally.
            ratisFuture.complete(OMRatisHelper.convertResponseToMessage(
                omResponse));
          }
        } else {
          ratisFuture.complete(OMRatisHelper.convertResponseToMessage(
              omResponse));
        }
        return ratisFuture;
      });
      return ratisFuture;
    } catch (Exception e) {
      return completeExceptionally(e);
    }
  }

  /**
   * Terminate OM.
   * @param omResponse
   * @param resultCode
   */
  private void terminate(OMResponse omResponse,
      OMException.ResultCodes resultCode) {
    OMException exception = new OMException(omResponse.getMessage(),
        resultCode);
    String errorMessage = "OM Response has received unrecoverable " +
        "error, terminating OM. Error Response is" + omResponse;
    ExitUtils.terminate(1, errorMessage, exception, LOG);
  }

  /**
   * Query the state machine. The request must be read-only.
   */
  @Override
  public CompletableFuture<Message> query(Message request) {
    try {
      OMRequest omRequest = OMRatisHelper.convertByteStringToOMRequest(
          request.getContent());
      return CompletableFuture.completedFuture(queryCommand(omRequest));
    } catch (IOException e) {
      return completeExceptionally(e);
    }
  }

  @Override
  public void pause() {
    lifeCycle.transition(LifeCycle.State.PAUSING);
    lifeCycle.transition(LifeCycle.State.PAUSED);
    ozoneManagerDoubleBuffer.stop();
  }

  /**
   * Unpause the StateMachine, re-initialize the DoubleBuffer and update the
   * lastAppliedIndex. This should be done after uploading new state to the
   * StateMachine.
   */
  public void unpause(long newLastAppliedSnaphsotIndex,
      long newLastAppliedSnapShotTermIndex) {
    lifeCycle.startAndTransition(() -> {
      this.ozoneManagerDoubleBuffer =
          new OzoneManagerDoubleBuffer(ozoneManager.getMetadataManager(),
              this::updateLastAppliedIndex);
      handler.updateDoubleBuffer(ozoneManagerDoubleBuffer);
      this.setLastAppliedTermIndex(TermIndex.newTermIndex(
          newLastAppliedSnapShotTermIndex, newLastAppliedSnaphsotIndex));
    });
  }

  /**
   * Take OM Ratis snapshot. Write the snapshot index to file. Snapshot index
   * is the log index corresponding to the last applied transaction on the OM
   * State Machine.
   *
   * @return the last applied index on the state machine which has been
   * stored in the snapshot file.
   */
  @Override
  public long takeSnapshot() throws IOException {
    LOG.info("Saving Ratis snapshot on the OM.");
    if (ozoneManager != null) {
      return ozoneManager.saveRatisSnapshot().getIndex();
    }
    return 0;
  }

  /**
   * Leader OM has purged entries from its log. To catch up, OM must download
   * the latest checkpoint from the leader OM and install it.
   * @param roleInfoProto the leader node information
   * @param firstTermIndexInLog TermIndex of the first append entry available
   *                           in the Leader's log.
   * @return the last term index included in the installed snapshot.
   */
  @Override
  public CompletableFuture<TermIndex> notifyInstallSnapshotFromLeader(
      RaftProtos.RoleInfoProto roleInfoProto, TermIndex firstTermIndexInLog) {

    String leaderNodeId = RaftPeerId.valueOf(roleInfoProto.getSelf().getId())
        .toString();

    LOG.info("Received install snapshot notificaiton form OM leader: {} with " +
            "term index: {}", leaderNodeId, firstTermIndexInLog);

    if (!roleInfoProto.getRole().equals(RaftProtos.RaftPeerRole.LEADER)) {
      // A non-leader Ratis server should not send this notification.
      LOG.error("Received Install Snapshot notification from non-leader OM " +
          "node: {}. Ignoring the notification.", leaderNodeId);
      return completeExceptionally(new OMException("Received notification to " +
          "install snaphost from non-leader OM node",
          OMException.ResultCodes.RATIS_ERROR));
    }

    CompletableFuture<TermIndex> future = CompletableFuture.supplyAsync(
        () -> ozoneManager.installSnapshot(leaderNodeId),
        installSnapshotExecutor);
    return future;
  }

  /**
   * Notifies the state machine that the raft peer is no longer leader.
   */
  @Override
  public void notifyNotLeader(Collection<TransactionContext> pendingEntries)
      throws IOException {
    omRatisServer.updateServerRole();
  }

  /**
   * Handle the RaftClientRequest and return TransactionContext object.
   * @param raftClientRequest
   * @param omRequest
   * @return TransactionContext
   */
  private TransactionContext handleStartTransactionRequests(
      RaftClientRequest raftClientRequest, OMRequest omRequest) {

    return TransactionContext.newBuilder()
        .setClientRequest(raftClientRequest)
        .setStateMachine(this)
        .setServerRole(RaftProtos.RaftPeerRole.LEADER)
        .setLogData(raftClientRequest.getMessage().getContent())
        .build();
  }

  /**
   * Submits write request to OM and returns the response Message.
   * @param request OMRequest
   * @return response from OM
   * @throws ServiceException
   */
  private OMResponse runCommand(OMRequest request, long trxLogIndex) {
    return handler.handleWriteRequest(request,
        trxLogIndex).getOMResponse();
  }

  /**
   * Update lastAppliedIndex term and it's corresponding term in the
   * stateMachine.
   * @param lastFlushedIndex
   */
  public synchronized void updateLastAppliedIndex(long lastFlushedIndex) {
    Long appliedTerm = null;
    long appliedIndex = -1;
    for(long i = getLastAppliedTermIndex().getIndex() + 1;
        i <= lastFlushedIndex; i++) {
      final Long removed = applyTransactionMap.remove(i);
      if (removed == null) {
        break;
      }
      appliedTerm = removed;
      appliedIndex = i;
    }
    if (appliedTerm != null) {
      updateLastAppliedTermIndex(appliedTerm, appliedIndex);
    }
  }

  public void updateLastAppliedIndexWithSnaphsotIndex() {
    // This is done, as we have a check in Ratis for not throwing
    // LeaderNotReadyException, it checks stateMachineIndex >= raftLog
    // nextIndex (placeHolderIndex).
    setLastAppliedTermIndex(TermIndex.newTermIndex(snapshotInfo.getTerm(),
        snapshotInfo.getIndex()));

  }

  /**
   * Submits read request to OM and returns the response Message.
   * @param request OMRequest
   * @return response from OM
   * @throws ServiceException
   */
  private Message queryCommand(OMRequest request) {
    OMResponse response = handler.handleReadRequest(request);
    return OMRatisHelper.convertResponseToMessage(response);
  }

  private static <T> CompletableFuture<T> completeExceptionally(Exception e) {
    final CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(e);
    return future;
  }

  @VisibleForTesting
  public void setHandler(OzoneManagerRequestHandler handler) {
    this.handler = handler;
  }

  @VisibleForTesting
  public void setRaftGroupId(RaftGroupId raftGroupId) {
    this.raftGroupId = raftGroupId;
  }

  public void stop() {
    ozoneManagerDoubleBuffer.stop();
    HadoopExecutors.shutdown(executorService, LOG, 5, TimeUnit.SECONDS);
    HadoopExecutors.shutdown(installSnapshotExecutor, LOG, 5, TimeUnit.SECONDS);
  }
}
