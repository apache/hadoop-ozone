#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

COMPOSE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
export COMPOSE_DIR

# shellcheck source=/dev/null
source "$COMPOSE_DIR/../testlib.sh"

export SECURITY_ENABLED=true

start_docker_env

execute_robot_test scm kinit.robot

execute_robot_test scm basic

execute_robot_test scm security

execute_robot_test scm -v SCHEME:ofs ozonefs/ozonefs.robot
execute_robot_test scm -v SCHEME:o3fs ozonefs/ozonefs.robot

execute_robot_test s3g s3

execute_command_in_container scm ozone sh volume create /legacy
execute_command_in_container scm ozone sh bucket create /legacy/source-bucket
# /s3v is already created in 's3' tests above
execute_command_in_container scm ozone sh bucket link /legacy/source-bucket /s3v/link
execute_robot_test s3g -v BUCKET=link s3

execute_robot_test scm admincli

execute_robot_test scm recon

execute_robot_test scm spnego

stop_docker_env

generate_report
