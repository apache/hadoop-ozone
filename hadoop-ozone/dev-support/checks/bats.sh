#!/usr/bin/env bash
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

set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "${DIR}/../../.." || exit 1

_install_tool_callback() {
  curl -LSs https://github.com/bats-core/bats-core/archive/v1.2.1.tar.gz | tar xzf -
}

source "${DIR}/_lib.sh"
install_tool
export PATH="${PATH}:${TOOL_DIR}/bats-core-1.2.1/bin"

REPORT_DIR=${OUTPUT_DIR:-"${DIR}/../../../target/bats"}
mkdir -p "${REPORT_DIR}"
REPORT_FILE="${REPORT_DIR}/summary.txt"

rm -f "${REPORT_DIR}/output.log"

find * -path '*/src/test/shell/*' -name '*.bats' -print0 \
  | xargs -0 -n1 bats --formatter tap \
  | tee -a "${REPORT_DIR}/output.log"

grep '^\(not ok\|#\)' "${REPORT_DIR}/output.log" > "${REPORT_FILE}"

grep -c '^not ok' "${REPORT_FILE}" > "${REPORT_DIR}/failures"

if [[ -s "${REPORT_FILE}" ]]; then
   exit 1
fi
