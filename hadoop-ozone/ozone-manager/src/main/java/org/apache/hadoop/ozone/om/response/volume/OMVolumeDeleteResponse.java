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

package org.apache.hadoop.ozone.om.response.volume;

import java.io.IOException;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .UserVolumeInfo;
import org.apache.hadoop.hdds.utils.db.BatchOperation;

import javax.annotation.Nonnull;

/**
 * Response for CreateVolume request.
 */
public class OMVolumeDeleteResponse extends OMClientResponse {
  private String volume;
  private String owner;
  private UserVolumeInfo updatedVolumeList;

  public OMVolumeDeleteResponse(@Nonnull OMResponse omResponse,
      @Nonnull String volume, @Nonnull String owner,
      @Nonnull UserVolumeInfo updatedVolumeList) {
    super(omResponse);
    this.volume = volume;
    this.owner = owner;
    this.updatedVolumeList = updatedVolumeList;
  }

  /**
   * For when the request is not successful or it is a replay transaction.
   * For a successful request, the other constructor should be used.
   */
  public OMVolumeDeleteResponse(@Nonnull OMResponse omResponse) {
    super(omResponse);
    checkStatusNotOK();
  }

  @Override
  protected void addToDBBatch(OMMetadataManager omMetadataManager,
      BatchOperation batchOperation) throws IOException {

    String dbUserKey = omMetadataManager.getUserKey(owner);
    UserVolumeInfo volumeList = updatedVolumeList;
    if (updatedVolumeList.getVolumeNamesList().size() == 0) {
      omMetadataManager.getUserTable().deleteWithBatch(batchOperation,
          dbUserKey);
    } else {
      omMetadataManager.getUserTable().putWithBatch(batchOperation, dbUserKey,
          volumeList);
    }
    omMetadataManager.getVolumeTable().deleteWithBatch(batchOperation,
        omMetadataManager.getVolumeKey(volume));
  }
}

