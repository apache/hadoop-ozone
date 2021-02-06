/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.scm;

import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ScmOps;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.safemode.Precheck;

import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.ha.ConfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalInt;

import static org.apache.hadoop.hdds.HddsUtils.getHostNameFromConfigKeys;
import static org.apache.hadoop.hdds.HddsUtils.getPortNumberFromConfigKeys;

/**
 * SCM utility class.
 */
public final class ScmUtils {
  private static final Logger LOG = LoggerFactory
      .getLogger(ScmUtils.class);

  private ScmUtils() {
  }

  /**
   * Perform all prechecks for given scm operation.
   *
   * @param operation
   * @param preChecks prechecks to be performed
   */
  public static void preCheck(ScmOps operation, Precheck... preChecks)
      throws SCMException {
    for (Precheck preCheck : preChecks) {
      preCheck.check(operation);
    }
  }

  /**
   * Create SCM directory file based on given path.
   */
  public static File createSCMDir(String dirPath) {
    File dirFile = new File(dirPath);
    if (!dirFile.mkdirs() && !dirFile.exists()) {
      throw new IllegalArgumentException("Unable to create path: " + dirFile);
    }
    return dirFile;
  }

  public static Collection<String> getSCMNodeIds(ConfigurationSource conf,
      String scmServiceId) {
    String key = ConfUtils.addSuffix(
        ScmConfigKeys.OZONE_SCM_NODES_KEY, scmServiceId);
    return conf.getTrimmedStringCollection(key);
  }

  public static InetSocketAddress getScmBlockProtocolServerAddress(
      OzoneConfiguration conf, String localScmServiceId, String nodeId) {
    String bindHostKey = ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_BIND_HOST_KEY,
        localScmServiceId, nodeId);
    final Optional<String> host = getHostNameFromConfigKeys(conf, bindHostKey);

    String addressKey = ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_ADDRESS_KEY,
        localScmServiceId, nodeId);
    final OptionalInt port = getPortNumberFromConfigKeys(conf, addressKey);

    return NetUtils.createSocketAddr(
        host.orElse(
            ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_BIND_HOST_DEFAULT) + ":" +
            port.orElse(ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_PORT_DEFAULT));
  }

  public static String getScmBlockProtocolServerAddressKey(
      String serviceId, String nodeId) {
    return ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_ADDRESS_KEY,
        serviceId, nodeId);
  }

  public static InetSocketAddress getClientProtocolServerAddress(
      OzoneConfiguration conf, String localScmServiceId, String nodeId) {
    String bindHostKey = ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_CLIENT_BIND_HOST_KEY,
        localScmServiceId, nodeId);

    final String host = getHostNameFromConfigKeys(conf, bindHostKey)
        .orElse(ScmConfigKeys.OZONE_SCM_CLIENT_BIND_HOST_DEFAULT);

    String addressKey = ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY,
        localScmServiceId, nodeId);

    final int port = getPortNumberFromConfigKeys(conf, addressKey)
        .orElse(ScmConfigKeys.OZONE_SCM_CLIENT_PORT_DEFAULT);

    return NetUtils.createSocketAddr(host + ":" + port);
  }

  public static String getClientProtocolServerAddressKey(
      String serviceId, String nodeId) {
    return ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY,
        serviceId, nodeId);
  }

  public static InetSocketAddress getScmDataNodeBindAddress(
      ConfigurationSource conf, String localScmServiceId, String nodeId) {
    String bindHostKey = ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_DATANODE_BIND_HOST_KEY,
        localScmServiceId, nodeId
    );
    final Optional<String> host = getHostNameFromConfigKeys(conf, bindHostKey);
    String addressKey = ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_DATANODE_ADDRESS_KEY,
        localScmServiceId, nodeId
    );
    final OptionalInt port = getPortNumberFromConfigKeys(conf, addressKey);

    return NetUtils.createSocketAddr(
        host.orElse(ScmConfigKeys.OZONE_SCM_DATANODE_BIND_HOST_DEFAULT) + ":" +
            port.orElse(ScmConfigKeys.OZONE_SCM_DATANODE_PORT_DEFAULT));
  }

  public static String getScmDataNodeBindAddressKey(
      String serviceId, String nodeId) {
    return ConfUtils.addKeySuffixes(
        ScmConfigKeys.OZONE_SCM_DATANODE_ADDRESS_KEY,
        serviceId, nodeId);
  }
}
