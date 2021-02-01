package org.apache.hadoop.hdds.scm.ha;/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * <p>Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Contract to download a SCM Snapshot from remote server..
 * <p>
 *
 * The underlying implementation is supposed to download SCM snapshot via
 * any chosen protoclol(for now its Grpc).
 * images.
 */
public interface SCMSnapshotDownloader {

  CompletableFuture<Path> download(final Path destination) throws IOException;

  void close() throws Exception;
}