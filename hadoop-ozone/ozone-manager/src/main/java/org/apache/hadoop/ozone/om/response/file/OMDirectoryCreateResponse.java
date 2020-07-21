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

package org.apache.hadoop.ozone.om.response.file;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.helpers.OmPrefixInfo;
import org.apache.hadoop.ozone.om.response.CleanupTableInfo;
import org.apache.hadoop.ozone.om.request.file.OMDirectoryCreateRequest.Result;

import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.KEY_TABLE;

/**
 * Response for create directory request.
 */
@CleanupTableInfo(cleanupTables = {KEY_TABLE})
public class OMDirectoryCreateResponse extends OMClientResponse {

  public static final Logger LOG =
      LoggerFactory.getLogger(OMDirectoryCreateResponse.class);

  private OmPrefixInfo prefixInfo;
  private List<OmPrefixInfo> parentPrefixInfos;
  private Result result;

  public OMDirectoryCreateResponse(@Nonnull OMResponse omResponse,
      @Nonnull OmPrefixInfo dirKeyInfo,
      @Nonnull List<OmPrefixInfo> parentDirInfos, @Nonnull Result result) {
    super(omResponse);
    this.prefixInfo = dirKeyInfo;
    this.parentPrefixInfos = parentDirInfos;
    this.result = result;
  }

  /**
   * For when the request is not successful or the directory already exists.
   */
  public OMDirectoryCreateResponse(@Nonnull OMResponse omResponse,
      @Nonnull Result result) {
    super(omResponse);
    this.result = result;
  }

  @Override
  protected void addToDBBatch(OMMetadataManager omMetadataManager,
                              BatchOperation batchOperation)
          throws IOException {
    addToPrefixTable(omMetadataManager, batchOperation);
  }

  private void addToPrefixTable(OMMetadataManager omMetadataManager,
                                BatchOperation batchOperation)
          throws IOException {
    if (prefixInfo != null) {
      if (parentPrefixInfos != null) {
        for (OmPrefixInfo parentDirInfo : parentPrefixInfos) {
          String parentKey = omMetadataManager
                  .getOzonePrefixKey(parentDirInfo.getParentObjectID(),
                          parentDirInfo.getName());
          LOG.debug("putWithBatch parent : dir {} info : {}", parentKey,
                  parentDirInfo);
          omMetadataManager.getPrefixTable()
                  .putWithBatch(batchOperation, parentKey, parentDirInfo);
        }
      }

      String dirKey = omMetadataManager.getOzonePrefixKey(
              prefixInfo.getParentObjectID(), prefixInfo.getName());
      omMetadataManager.getPrefixTable().putWithBatch(batchOperation, dirKey,
              prefixInfo);
    } else {
      // When directory already exists, we don't add it to cache. And it is
      // not an error, in this case dirKeyInfo will be null.
      LOG.debug("Response Status is OK, dirKeyInfo is null in " +
              "OMDirectoryCreateResponse");
    }
  }
}
