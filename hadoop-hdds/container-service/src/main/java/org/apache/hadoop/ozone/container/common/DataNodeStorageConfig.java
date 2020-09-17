/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.container.common;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeType;
import org.apache.hadoop.ozone.common.Storage;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static org.apache.hadoop.hdds.server.ServerUtils.getOzoneMetaDirPath;
import static org.apache.hadoop.ozone.OzoneConsts.DATANODE_STORAGE_CONFIG;

/**
 * DataNodeStorageConfig is responsible for management of the
 * StorageDirectories used by the DataNode.
 */
public class DataNodeStorageConfig extends Storage {

  /**
   * Construct DataNodeStorageConfig.
   * @throws IOException if any directories are inaccessible.
   */
  public DataNodeStorageConfig(OzoneConfiguration conf) throws IOException {
    super(NodeType.DATANODE, getOzoneMetaDirPath(conf),
        DATANODE_STORAGE_CONFIG);
  }

  public DataNodeStorageConfig(NodeType type, File root, String sdName)
      throws IOException {
    super(type, root, sdName);
  }

  @Override
  protected Properties getNodeProperties() {
    // No additional properties for now.
    return null;
  }
}
