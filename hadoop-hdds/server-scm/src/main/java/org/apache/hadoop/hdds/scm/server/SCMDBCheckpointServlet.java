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

package org.apache.hadoop.hdds.scm.server;

import javax.servlet.ServletException;
import org.apache.hadoop.hdds.utils.DBCheckpointServlet;
import org.apache.hadoop.ozone.OzoneConsts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Provides the current checkpoint Snapshot of the OM DB. (tar.gz)
 */
public class SCMDBCheckpointServlet extends DBCheckpointServlet {

  private static final Logger LOG =
      LoggerFactory.getLogger(SCMDBCheckpointServlet.class);
  private static final long serialVersionUID = 1L;

  private transient StorageContainerManager scm;

  @Override
  public void init() throws ServletException {

    scm = (StorageContainerManager) getServletContext()
        .getAttribute(OzoneConsts.SCM_CONTEXT_ATTRIBUTE);

    if (scm == null) {
      LOG.error("Unable to initialize SCMDBCheckpointServlet. SCM is null");
      return;
    }

    initialize(scm.getScmMetadataStore().getStore(),
        scm.getMetrics().getDBCheckpointMetrics(),
        false,
        Collections.emptyList());
  }
}
