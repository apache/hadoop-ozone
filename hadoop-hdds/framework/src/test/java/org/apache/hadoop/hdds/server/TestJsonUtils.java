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
package org.apache.hadoop.hdds.server;

import java.io.IOException;

import org.apache.hadoop.hdds.client.OzoneQuota;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Test the json object printer.
 */
public class TestJsonUtils {

  @Test
  public void printObjectAsJson() throws IOException {
    OzoneQuota quota = OzoneQuota.parseQuota("123MB", 1000L);

    String result = JsonUtils.toJsonStringWithDefaultPrettyPrinter(quota);

    assertContains(result, "\"rawSize\" : 123");
    assertContains(result, "\"unit\" : \"MB\"");
    assertContains(result, "\"quotaInCounts\" : 1000");
  }

  private static void assertContains(String str, String part) {
    assertTrue("Expected JSON to contain '" + part + "', but didn't: " + str,
        str.contains(part));
  }
}
