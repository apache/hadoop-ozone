/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.debug;

import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_CONTAINER_KEY_DB;
import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_OM_SNAPSHOT_DB;
import static org.apache.hadoop.ozone.recon.scm.ReconSCMDBDefinition.RECON_SCM_DB_NAME;

import org.apache.hadoop.hdds.scm.metadata.SCMDBDefinition;
import org.apache.hadoop.hdds.utils.db.DBDefinition;
import org.apache.hadoop.ozone.om.codec.OMDBDefinition;
import org.apache.hadoop.ozone.recon.scm.ReconSCMDBDefinition;
import org.apache.hadoop.ozone.recon.spi.impl.ReconDBDefinition;

import java.util.HashMap;

/**
 * Utility class to get appropriate DBDefinition.
 */
public final class DBDefinitionFactory {

  private DBDefinitionFactory() {
  }

  private static HashMap<String, DBDefinition> dbMap;

  static {
    dbMap = new HashMap<>();
    dbMap.put(new SCMDBDefinition().getName(), new SCMDBDefinition());
    dbMap.put(new OMDBDefinition().getName(), new OMDBDefinition());
  }

  public static DBDefinition getDefinition(String dbName, String type){
    if (dbMap.containsKey(dbName)){
      return dbMap.get(dbName);
    }
    if (type != null && type.equals("recon")) {
      return getReconDBDefinition(dbName);
    }
    return null;
  }

  private static DBDefinition getReconDBDefinition(String dbName){
    if (dbName.equals(RECON_SCM_DB_NAME)) {
      return new ReconSCMDBDefinition();
    } else if (dbName.startsWith(RECON_CONTAINER_KEY_DB)) {
      return new ReconDBDefinition(dbName);
    } else if (dbName.startsWith(RECON_OM_SNAPSHOT_DB)) {
      return new OMDBDefinition();
    }
    return null;
  }
}
