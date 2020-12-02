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

package org.apache.hadoop.hdds.client;

import java.util.ArrayList;

/**
 *This class contains arraylist for storage constant used in OzoneQuota.
 */
public class QuotaList {
    public ArrayList<String> OZONE_QUOTA;
    public ArrayList<OzoneQuota.Units> unitQuota;
    public ArrayList<Long> sizeQuota;

    public QuotaList(){
        OZONE_QUOTA = new ArrayList<String>();
        unitQuota = new ArrayList<OzoneQuota.Units>();
        sizeQuota = new ArrayList<Long>();
    }

    public void addQuotaList(String oQuota, OzoneQuota.Units uQuota, Long sQuota){
        OZONE_QUOTA.add(oQuota);
        unitQuota.add(uQuota);
        sizeQuota.add(sQuota);
    }

    public OzoneQuota.Units getUnits(String oQuota){
        return unitQuota.get(OZONE_QUOTA.indexOf(oQuota));
    }

    public Long getQuotaSize(OzoneQuota.Units uQuota){
        return sizeQuota.get(unitQuota.indexOf(uQuota));
    }

    public OzoneQuota.Units getQuotaUnit(Long sQuota){
        return unitQuota.get(sizeQuota.indexOf(sQuota));
    }

}
