
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

*** Settings ***
Documentation       Smoke test for spnego with docker-compose environments.
Library             OperatingSystem
Library             String
Library             BuiltIn
Resource            ../commonlib.robot
Test Timeout        5 minutes

*** Variables ***
${OM_URL}       http://om:9874
${OM_DB_CHECKPOINT_URL}       http://om:9874/dbCheckpoint
${OM_SERVICE_LIST_URL}       http://om:9874/serviceList

${SCM_URL}       http://scm:9876
${RECON_URL}       http://recon:9888

*** Keywords ***
Verify SPNEGO enabled URL
    [arguments]                      ${url}
    Run Keyword if      '${SECURITY_ENABLED}' == 'true'     Execute     kdestroy
    ${result} =         Execute                             curl --negotiate -u : -v -s -I ${url}
    Should contain      ${result}       401 Unauthorized

    Run Keyword if      '${SECURITY_ENABLED}' == 'true'     Kinit test user     testuser     testuser.keytab
    ${result} =         Execute                             curl --negotiate -u : -v -s -I ${url}
    Should contain      ${result}       200 OK



*** Test Cases ***
Generate Freon data
    Run Keyword if      '${SECURITY_ENABLED}' == 'true'     Kinit test user     testuser     testuser.keytab
                        Execute                             ozone freon rk --replicationType=RATIS --numOfVolumes 1 --numOfBuckets 1 --numOfKeys 2 --keySize 1025

Test OM portal
    Verify SPNEGO enabled URL       ${OM_URL}

Test OM DB Checkpoint
    Verify SPNEGO enabled URL       ${OM_DB_CHECKPOINT_URL}

Test OM Service List
    Verify SPNEGO enabled URL       ${OM_SERVICE_LIST_URL}

Test SCM portal
    Verify SPNEGO enabled URL       ${SCM_URL}

Test Recon portal
    Verify SPNEGO enabled URL       ${RECON_URL}

