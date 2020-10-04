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

package org.apache.hadoop.ozone.om.request.volume;

import java.io.IOException;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerDoubleBufferHelper;
import org.apache.hadoop.ozone.om.request.util.OmResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.ozone.audit.OMAction;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.volume.OMVolumeDeleteResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .DeleteVolumeRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .DeleteVolumeResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;

import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.VOLUME_LOCK;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.USER_LOCK;
/**
 * Handles volume delete request.
 */
public class OMVolumeDeleteRequest extends OMVolumeRequest {

  private static final Logger LOG =
      LoggerFactory.getLogger(OMVolumeDeleteRequest.class);

  public OMVolumeDeleteRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public OMClientResponse validateAndUpdateCache(OzoneManager ozoneManager,
      long transactionLogIndex,
      OzoneManagerDoubleBufferHelper ozoneManagerDoubleBufferHelper) {

    DeleteVolumeRequest deleteVolumeRequest =
        getOmRequest().getDeleteVolumeRequest();
    Preconditions.checkNotNull(deleteVolumeRequest);

    String volume = deleteVolumeRequest.getVolumeName();

    OMMetrics omMetrics = ozoneManager.getMetrics();
    omMetrics.incNumVolumeDeletes();

    OMResponse.Builder omResponse = OmResponseUtil.getOMResponseBuilder(
        getOmRequest());

    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    boolean acquiredUserLock = false;
    boolean acquiredVolumeLock = false;
    IOException exception = null;
    String owner = null;
    OMClientResponse omClientResponse = null;
    try {
      // check Acl
      if (ozoneManager.getAclsEnabled()) {
        checkAcls(ozoneManager, OzoneObj.ResourceType.VOLUME,
            OzoneObj.StoreType.OZONE, IAccessAuthorizer.ACLType.DELETE, volume,
            null, null, null);
      }

      acquiredVolumeLock = omMetadataManager.getLock().acquireWriteLock(
          VOLUME_LOCK, volume);

      OmVolumeArgs omVolumeArgs = getVolumeInfo(omMetadataManager, volume);

      owner = omVolumeArgs.getOwnerName();
      acquiredUserLock = omMetadataManager.getLock().acquireWriteLock(USER_LOCK,
          owner);

      String dbUserKey = omMetadataManager.getUserKey(owner);
      String dbVolumeKey = omMetadataManager.getVolumeKey(volume);

      if (!omMetadataManager.isVolumeEmpty(volume)) {
        LOG.debug("volume:{} is not empty", volume);
        throw new OMException(OMException.ResultCodes.VOLUME_NOT_EMPTY);
      }

      OzoneManagerProtocolProtos.UserVolumeInfo newVolumeList =
          omMetadataManager.getUserTable().get(owner);

      // delete the volume from the owner list
      // as well as delete the volume entry
      newVolumeList = delVolumeFromOwnerList(newVolumeList, volume, owner,
          transactionLogIndex);

      omMetadataManager.getUserTable().addCacheEntry(new CacheKey<>(dbUserKey),
          new CacheValue<>(Optional.of(newVolumeList), transactionLogIndex));

      omMetadataManager.getVolumeTable().addCacheEntry(
          new CacheKey<>(dbVolumeKey), new CacheValue<>(Optional.absent(),
              transactionLogIndex));

      omResponse.setDeleteVolumeResponse(
          DeleteVolumeResponse.newBuilder().build());
      omClientResponse = new OMVolumeDeleteResponse(omResponse.build(),
          volume, owner, newVolumeList);

    } catch (IOException ex) {
      exception = ex;
      omClientResponse = new OMVolumeDeleteResponse(
          createErrorOMResponse(omResponse, exception));
    } finally {
      addResponseToDoubleBuffer(transactionLogIndex, omClientResponse,
          ozoneManagerDoubleBufferHelper);
      if (acquiredUserLock) {
        omMetadataManager.getLock().releaseWriteLock(USER_LOCK, owner);
      }
      if (acquiredVolumeLock) {
        omMetadataManager.getLock().releaseWriteLock(VOLUME_LOCK, volume);
      }
    }

    // Performing audit logging outside of the lock.
    auditLog(ozoneManager.getAuditLogger(),
        buildAuditMessage(OMAction.DELETE_VOLUME, buildVolumeAuditMap(volume),
            exception, getOmRequest().getUserInfo()));

    // return response after releasing lock.
    if (exception == null) {
      LOG.debug("Volume deleted for user:{} volume:{}", owner, volume);
      omMetrics.decNumVolumes();
    } else {
      LOG.error("Volume deletion failed for user:{} volume:{}",
          owner, volume, exception);
      omMetrics.incNumVolumeDeleteFails();
    }
    return omClientResponse;
  }
}

