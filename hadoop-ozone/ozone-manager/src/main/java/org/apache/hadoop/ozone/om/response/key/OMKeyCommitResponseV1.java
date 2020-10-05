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

import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.response.CleanupTableInfo;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;

import javax.annotation.Nonnull;

import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.*;

/**
 * Response for CommitKey request layout version V1.
 */
@CleanupTableInfo(cleanupTables = {OPEN_KEY_TABLE, KEY_TABLE,
        OPEN_FILE_TABLE, FILE_TABLE})
public class OMKeyCommitResponseV1 extends OMKeyCommitResponse {

  private OmKeyInfo omKeyInfo;
  private String ozoneKeyName;
  private String openKeyName;

  public OMKeyCommitResponseV1(@Nonnull OMResponse omResponse,
                               @Nonnull OmKeyInfo omKeyInfo,
                               String ozoneKeyName, String openKeyName,
                               @Nonnull OmVolumeArgs omVolumeArgs,
                               @Nonnull OmBucketInfo omBucketInfo) {
    super(omResponse, omKeyInfo, ozoneKeyName, openKeyName, omVolumeArgs,
            omBucketInfo);
    this.omKeyInfo = omKeyInfo;
    this.ozoneKeyName = ozoneKeyName;
    this.openKeyName = openKeyName;
  }

  /**
   * For when the request is not successful.
   * For a successful request, the other constructor should be used.
   */
  public OMKeyCommitResponseV1(@Nonnull OMResponse omResponse) {
    super(omResponse);
    checkStatusNotOK();
  }
}
