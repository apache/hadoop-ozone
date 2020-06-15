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
package org.apache.hadoop.ozone.om.helpers;

import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;

import java.util.*;

public class OmDirectoryInfo extends WithObjectID {
  private final String dirSeparator = "/";

  private long parentObjectID; // pointer to parent directory
  private short index; // position of this directory in the path

  private String name;
  private String volumeName;
  private String bucketName;

  private long creationTime;
  private long modificationTime;

  private List<OzoneAcl> acls;

  public OmDirectoryInfo(Builder builder) {
    this.name = builder.name;
    this.acls = builder.acls;
    this.metadata = builder.metadata;
    this.objectID = builder.objectID;
    this.updateID = builder.updateID;
    this.parentObjectID = builder.parentObjectID;
    this.index = builder.index;
    this.volumeName = builder.volumeName;
    this.bucketName = builder.bucketName;
    this.creationTime = builder.creationTime;
    this.modificationTime = builder.modificationTime;
  }

  // TODO: its work around and need to remove the dependency with OMKeyInfo
  public static OmDirectoryInfo createDirectoryInfo(OmKeyInfo omKeyInfo,
                                             long parentObjectID) {
    String dirName = OzoneFSUtils.getFileName(omKeyInfo.getKeyName());
    return new Builder().setName(dirName)
            .setParentObjectID(parentObjectID)
            .setObjectID(omKeyInfo.getObjectID())
            .setUpdateID(omKeyInfo.getUpdateID())
            .setBucketName(omKeyInfo.getBucketName())
            .setVolumeName(omKeyInfo.getVolumeName())
            .setCreationTime(omKeyInfo.getCreationTime())
            .setModificationTime(omKeyInfo.getModificationTime())
            .setAcls(omKeyInfo.getAcls())
            .setMetadata(omKeyInfo.getMetadata())
            .build();
  }

  // TODO: move to builder and check the necessity of persisting it
  public void setIndex(short index) {
    this.index = index;
  }

  /**
   * Returns new builder class that builds a OmPrefixInfo.
   *
   * @return Builder
   */
  public static OmDirectoryInfo.Builder newBuilder() {
    return new OmDirectoryInfo.Builder();
  }

  public static class Builder {
    private long parentObjectID; // pointer to parent directory
    private short index; // position of this directory in the path

    private long objectID;
    private long updateID;

    private String name;
    private String volumeName;
    private String bucketName;


    private long creationTime;
    private long modificationTime;

    private List<OzoneAcl> acls;
    private Map<String, String> metadata;

    public Builder() {
      //Default values
      this.acls = new LinkedList<>();
      this.metadata = new HashMap<>();
    }

    public Builder setParentObjectID(long parentObjectID) {
      this.parentObjectID = parentObjectID;
      return this;
    }

    public Builder setObjectID(long objectID) {
      this.objectID = objectID;
      return this;
    }

    public Builder setUpdateID(long updateID) {
      this.updateID = updateID;
      return this;
    }

    public Builder setIndex(short index) {
      this.index = index;
      return this;
    }

    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    public Builder setVolumeName(String volumeName) {
      this.volumeName = volumeName;
      return this;
    }

    public Builder setBucketName(String bucketName) {
      this.bucketName = bucketName;
      return this;
    }

    public Builder setCreationTime(long creationTime) {
      this.creationTime = creationTime;
      return this;
    }

    public Builder setModificationTime(long modificationTime) {
      this.modificationTime = modificationTime;
      return this;
    }

    public Builder setAcls(List<OzoneAcl> acls) {
      this.acls = acls;
      return this;
    }

    public Builder addAcl(OzoneAcl ozoneAcl) {
      if (ozoneAcl != null) {
        this.acls.add(ozoneAcl);
      }
      return this;
    }

    public Builder setMetadata(Map<String, String> metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder addMetadata(String key, String value) {
      metadata.put(key, value);
      return this;
    }

    public Builder addAllMetadata(Map<String, String> additionalMetadata) {
      if (additionalMetadata != null) {
        metadata.putAll(additionalMetadata);
      }
      return this;
    }

    public OmDirectoryInfo build() {
      return new OmDirectoryInfo(this);
    }
  }

  @Override
  public String toString() {
    return getObjectID() + "";
  }

  public String getVolumeName() {
    return volumeName;
  }

  public String getBucketName() {
    return bucketName;
  }

  public long getParentObjectID() {
    return parentObjectID;
  }

  public String getPath() {
    return getParentObjectID() + dirSeparator + getName();
  }

  public int getIndex() { return index; }

  public String getName() { return name; }

  public long getCreationTime() {
    return creationTime;
  }

  public long getModificationTime() {
    return modificationTime;
  }

  public List<OzoneAcl> getAcls() { return acls; }

  /**
   * Creates PrefixInfo protobuf from OmDirectoryInfo.
   */
  public OzoneManagerProtocolProtos.DirectoryInfo getProtobuf() {
    OzoneManagerProtocolProtos.DirectoryInfo.Builder pib =
            OzoneManagerProtocolProtos.DirectoryInfo.newBuilder().setName(name)
                    .setVolumeName(volumeName)
                    .setBucketName(bucketName)
                    .setCreationTime(creationTime)
                    .setModificationTime(modificationTime)
                    .addAllMetadata(KeyValueUtil.toProtobuf(metadata))
                    .addAllAcls(OzoneAclUtil.toProtobuf(acls))
                    .setObjectID(objectID)
                    .setUpdateID(updateID)
                    .setParentID(parentObjectID);
    if (acls != null) {
      pib.addAllAcls(OzoneAclUtil.toProtobuf(acls));
    }
    return pib.build();
  }

  /**
   * Parses DirectoryInfo protobuf and creates OmPrefixInfo.
   * @param dirInfo
   * @return instance of OmDirectoryInfo
   */
  public static OmDirectoryInfo getFromProtobuf(
          OzoneManagerProtocolProtos.DirectoryInfo dirInfo) {
    OmDirectoryInfo.Builder opib = OmDirectoryInfo.newBuilder()
            .setVolumeName(dirInfo.getVolumeName())
            .setBucketName(dirInfo.getBucketName())
            .setName(dirInfo.getName())
            .setCreationTime(dirInfo.getCreationTime())
            .setModificationTime(dirInfo.getModificationTime())
            .setAcls(OzoneAclUtil.fromProtobuf(dirInfo.getAclsList()));
    if (dirInfo.getMetadataList() != null) {
      opib.addAllMetadata(KeyValueUtil
              .getFromProtobuf(dirInfo.getMetadataList()));
    }
    if (dirInfo.hasObjectID()) {
      opib.setObjectID(dirInfo.getObjectID());
    }
    if (dirInfo.hasParentID()) {
      opib.setParentObjectID(dirInfo.getParentID());
    }
    if (dirInfo.hasUpdateID()) {
      opib.setUpdateID(dirInfo.getUpdateID());
    }
    return opib.build();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OmDirectoryInfo omDirInfo = (OmDirectoryInfo) o;
    return creationTime == omDirInfo.creationTime &&
            modificationTime == omDirInfo.modificationTime &&
            volumeName.equals(omDirInfo.volumeName) &&
            bucketName.equals(omDirInfo.bucketName) &&
            name.equals(omDirInfo.name) &&
            Objects.equals(metadata, omDirInfo.metadata) &&
            Objects.equals(acls, omDirInfo.acls) &&
            objectID == omDirInfo.objectID &&
            updateID == omDirInfo.updateID &&
            parentObjectID == omDirInfo.parentObjectID;
  }

  @Override
  public int hashCode() {
    return Objects.hash(volumeName, bucketName, parentObjectID, name);
  }

  /**
   * Return a new copy of the object.
   */
  public OmDirectoryInfo copyObject() {
    OmDirectoryInfo.Builder builder = new Builder()
            .setVolumeName(volumeName)
            .setBucketName(bucketName)
            .setName(name)
            .setCreationTime(creationTime)
            .setModificationTime(modificationTime)
            .setParentObjectID(parentObjectID)
            .setObjectID(objectID)
            .setIndex(index)
            .setUpdateID(updateID);

    acls.forEach(acl -> builder.addAcl(new OzoneAcl(acl.getType(),
            acl.getName(), (BitSet) acl.getAclBitSet().clone(),
            acl.getAclScope())));

    if (metadata != null) {
      metadata.forEach((k, v) -> builder.addMetadata(k, v));
    }

    return builder.build();
  }
}
