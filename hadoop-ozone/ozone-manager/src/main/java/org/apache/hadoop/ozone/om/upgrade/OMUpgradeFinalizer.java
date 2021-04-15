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

package org.apache.hadoop.ozone.om.upgrade;

import static org.apache.hadoop.ozone.OzoneConsts.LAYOUT_VERSION_KEY;

import org.apache.hadoop.ozone.common.Storage;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;

import java.io.IOException;

import org.apache.hadoop.ozone.upgrade.BasicUpgradeFinalizer;
import org.apache.hadoop.ozone.upgrade.UpgradeException;

/**
 * UpgradeFinalizer implementation for the Ozone Manager service.
 */
public class OMUpgradeFinalizer extends BasicUpgradeFinalizer<OzoneManager,
    OMLayoutVersionManager> {

  public OMUpgradeFinalizer(OMLayoutVersionManager versionManager) {
    super(versionManager);
  }

  @Override
  public StatusAndMessages finalize(String upgradeClientID, OzoneManager om)
      throws IOException {
    return super.finalize(upgradeClientID, om);
  }

  @Override
  public void postFinalizeUpgrade(OzoneManager om) {
    return;
  }

  @Override
  public void finalizeUpgrade(OzoneManager om)
      throws UpgradeException {
    super.finalizeUpgrade(om::getOmStorage);
  }

  @Override
  public void preFinalizeUpgrade(OzoneManager om) {
  }

  public void runPrefinalizeStateActions(Storage storage, OzoneManager om)
      throws IOException {
    super.runPrefinalizeStateActions(
        lf -> ((OMLayoutFeature) lf)::action, storage, om);
  }

  /**
   * Write down Layout version of a finalized feature to DB on finalization.
   * @param f layout feature
   * @param om OM instance
   * @throws IOException on Error.
   */
  @Override
  public void updateLayoutVersionInDB(OMLayoutVersionManager lvm,
                                      OzoneManager om)
      throws IOException {
    OMMetadataManager omMetadataManager = om.getMetadataManager();
    omMetadataManager.getMetaTable().put(LAYOUT_VERSION_KEY,
        String.valueOf(lvm.getMetadataLayoutVersion()));
  }
}
