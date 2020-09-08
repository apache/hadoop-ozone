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

package org.apache.hadoop.hdds.scm.ha;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.ratis.protocol.NotLeaderException;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;

/**
 * Mock SCMHAManager implementation for testing.
 */
public final class MockSCMHAManager implements SCMHAManager {

  private final SCMRatisServer ratisServer;
  private boolean isLeader;

  public static SCMHAManager getInstance() {
    return new MockSCMHAManager();
  }

  public static SCMHAManager getLeaderInstance() {
    MockSCMHAManager mockSCMHAManager = new MockSCMHAManager();
    mockSCMHAManager.setIsLeader(true);
    return mockSCMHAManager;
  }

  public static SCMHAManager getFollowerInstance() {
    MockSCMHAManager mockSCMHAManager = new MockSCMHAManager();
    mockSCMHAManager.setIsLeader(false);
    return mockSCMHAManager;
  }

  /**
   * Creates MockSCMHAManager instance.
   */
  private MockSCMHAManager() {
    this.ratisServer = new MockRatisServer();
    this.isLeader = true;
  }

  @Override
  public void start() throws IOException {
    ratisServer.start();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isLeader() {
    return isLeader;
  }

  public void setIsLeader(boolean isLeader) {
    this.isLeader = isLeader;
  }

  @Override
  public RaftPeer getSuggestedLeader() {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SCMRatisServer getRatisServer() {
    return ratisServer;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void shutdown() throws IOException {
    ratisServer.stop();
  }

  @Override
  public List<String> getRatisRoles() {
    return Arrays.asList(
        "180.3.14.5:9865",
        "180.3.14.21:9865",
        "180.3.14.145:9865");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public NotLeaderException triggerNotLeaderException() {
    return new NotLeaderException(RaftGroupMemberId.valueOf(
        RaftPeerId.valueOf("peer"), RaftGroupId.randomId()),
        null, new ArrayList<>());
  }
}