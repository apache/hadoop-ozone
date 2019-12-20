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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.om.response.s3.bucket;

import javax.annotation.Nonnull;
import java.io.IOException;

import com.google.common.base.Preconditions;
import com.google.common.annotations.VisibleForTesting;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.bucket.OMBucketCreateResponse;
import org.apache.hadoop.ozone.om.response.volume.OMVolumeCreateResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.hdds.utils.db.BatchOperation;

/**
 * Response for S3Bucket create request.
 */
public class S3BucketCreateResponse extends OMClientResponse {

  private OMVolumeCreateResponse omVolumeCreateResponse;
  private OMBucketCreateResponse omBucketCreateResponse;
  private String s3Bucket;
  private String s3Mapping;

  public S3BucketCreateResponse(@Nonnull OMResponse omResponse,
      @Nonnull OMVolumeCreateResponse omVolumeCreateResponse,
      @Nonnull OMBucketCreateResponse omBucketCreateResponse,
      @Nonnull String s3BucketName,
      @Nonnull String s3Mapping) {
    super(omResponse);
    this.omVolumeCreateResponse = omVolumeCreateResponse;
    this.omBucketCreateResponse = omBucketCreateResponse;
    this.s3Bucket = s3BucketName;
    this.s3Mapping = s3Mapping;
  }

  /**
   * For when the request is not successful or it is a replay transaction.
   * For a successful request, the other constructor should be used.
   */
  public S3BucketCreateResponse(@Nonnull OMResponse omResponse) {
    super(omResponse);
    checkStatusNotOK();
  }

  @Override
  protected void addToDBBatch(OMMetadataManager omMetadataManager,
      BatchOperation batchOperation) throws IOException {

    if (omVolumeCreateResponse != null) {
      omVolumeCreateResponse.checkAndUpdateDB(omMetadataManager,
          batchOperation);
    }

    Preconditions.checkState(omBucketCreateResponse != null);
    omBucketCreateResponse.checkAndUpdateDB(omMetadataManager, batchOperation);

    omMetadataManager.getS3Table().putWithBatch(batchOperation, s3Bucket,
        s3Mapping);
  }

  @VisibleForTesting
  public String getS3Mapping() {
    return s3Mapping;
  }
}
