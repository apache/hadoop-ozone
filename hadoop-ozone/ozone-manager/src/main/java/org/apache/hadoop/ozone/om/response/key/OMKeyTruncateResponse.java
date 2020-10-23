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

package org.apache.hadoop.ozone.om.response.key;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.response.CleanupTableInfo;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.hdds.utils.db.BatchOperation;

import java.io.IOException;
import javax.annotation.Nonnull;

import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.KEY_TABLE;

/**
 * Response for TruncateKey request.
 */
@CleanupTableInfo(cleanupTables = {KEY_TABLE})
public class OMKeyTruncateResponse extends OMClientResponse {

  private OmKeyInfo omKeyInfo;
  private OmVolumeArgs omVolumeArgs;
  private OmBucketInfo omBucketInfo;

  public OMKeyTruncateResponse(@Nonnull OMResponse omResponse,
      @Nonnull OmKeyInfo omKeyInfo, @Nonnull OmVolumeArgs omVolumeArgs,
      @Nonnull OmBucketInfo omBucketInfo) {
    super(omResponse);
    this.omKeyInfo = omKeyInfo;
    this.omVolumeArgs = omVolumeArgs;
    this.omBucketInfo = omBucketInfo;
  }

  /**
   * For when the request is not successful.
   * For a successful request, the other constructor should be used.
   */
  public OMKeyTruncateResponse(@Nonnull OMResponse omResponse) {
    super(omResponse);
  }

  @Override
  public void addToDBBatch(OMMetadataManager omMetadataManager,
      BatchOperation batchOperation) throws IOException {

    // For OmResponse with failure, this should do nothing. This method is
    // not called in failure scenario in OM code.
    String ozoneKey = omMetadataManager.getOzoneKey(omKeyInfo.getVolumeName(),
        omKeyInfo.getBucketName(), omKeyInfo.getKeyName());

    // RocksDB.put can overwrite the duplicated key, so we do not need
    // RocksDB.delete before RocksDB.put. But if we use other DB,
    // maybe DB.put can not support overwrite, so before DB.put,
    // we delete the existed key.
    omMetadataManager.getKeyTable().deleteWithBatch(batchOperation, ozoneKey);
    omMetadataManager.getKeyTable().putWithBatch(
        batchOperation, ozoneKey, omKeyInfo);

    // update volume usedBytes.
    omMetadataManager.getVolumeTable().putWithBatch(batchOperation,
        omMetadataManager.getVolumeKey(omVolumeArgs.getVolume()),
        omVolumeArgs);
    // update bucket usedBytes.
    omMetadataManager.getBucketTable().putWithBatch(batchOperation,
        omMetadataManager.getBucketKey(omVolumeArgs.getVolume(),
            omBucketInfo.getBucketName()), omBucketInfo);
  }
}
