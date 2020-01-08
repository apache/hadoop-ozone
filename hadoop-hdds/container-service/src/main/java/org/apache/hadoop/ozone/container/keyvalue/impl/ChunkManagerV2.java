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
package org.apache.hadoop.ozone.container.keyvalue.impl;

import com.google.common.base.Preconditions;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.ozone.common.ChunkBuffer;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfo;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.VolumeIOStats;
import org.apache.hadoop.ozone.container.keyvalue.helpers.ChunkUtils;
import org.apache.hadoop.ozone.container.keyvalue.interfaces.ChunkManager;
import org.apache.hadoop.ozone.container.common.interfaces.Container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.UNSUPPORTED_REQUEST;
import static org.apache.hadoop.ozone.container.common.impl.ChunkLayOutVersion.V2;
import static org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext.WriteChunkStage.COMBINED;
import static org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext.WriteChunkStage.COMMIT_DATA;
import static org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext.WriteChunkStage.WRITE_DATA;

/**
 * This class is for performing chunk related operations.
 */
public class ChunkManagerV2 implements ChunkManager {

  private static final Logger LOG =
      LoggerFactory.getLogger(ChunkManagerV2.class);

  private final boolean doSyncWrite;
  private final OpenFiles files = new OpenFiles();

  ChunkManagerV2(boolean sync) {
    doSyncWrite = sync;
  }

  private static void checkLayoutVersion(Container container) {
    Preconditions.checkArgument(
        container.getContainerData().getLayOutVersion() == V2);
  }

  @Override
  public void writeChunk(Container container, BlockID blockID, ChunkInfo info,
      ChunkBuffer data, DispatcherContext dispatcherContext)
      throws StorageContainerException {

    checkLayoutVersion(container);

    Preconditions.checkNotNull(dispatcherContext);
    DispatcherContext.WriteChunkStage stage = dispatcherContext.getStage();

    if (info.getLen() <= 0) {
      LOG.debug("Skip writing empty chunk {} in stage {}", info, stage);
      return;
    }

    KeyValueContainerData containerData = (KeyValueContainerData) container
        .getContainerData();

    long len = info.getLen();
    if (stage == WRITE_DATA || stage == COMBINED) {
      long offset = info.getOffset();
      File chunkFile = getChunkFile(containerData, info.getChunkName());
      validateChunkFileSize(info, chunkFile);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Writing chunk {} in stage {} to file {}",
            info, stage, chunkFile);
      }

      HddsVolume volume = containerData.getVolume();
      VolumeIOStats volumeIOStats = volume.getVolumeIOStats();

      FileChannel channel = files.getChannel(chunkFile, doSyncWrite);
      ChunkUtils.writeData(channel, chunkFile.getName(), data, offset, len,
          volumeIOStats);
    }

    if (stage == COMMIT_DATA || stage == COMBINED) {
      containerData.updateWriteStats(len, false);
    }
  }

  private static void validateChunkFileSize(ChunkInfo info, File chunkFile)
      throws StorageContainerException {

    long size = chunkFile.length();
    long offset = info.getOffset();
    if (offset != size) {
      String message = String.format(
          "Unexpected offset for %s, got: %d, expected %d",
          info.getChunkName(), offset, size);
      LOG.warn(message);
      throw new StorageContainerException(message, UNSUPPORTED_REQUEST);
    }
  }

  @Override
  public ChunkBuffer readChunk(Container container, BlockID blockID,
      ChunkInfo info, DispatcherContext dispatcherContext)
      throws StorageContainerException {

    checkLayoutVersion(container);

    if (info.getLen() <= 0) {
      LOG.debug("Skip reading empty chunk {}", info);
      return ChunkBuffer.wrap(ByteBuffer.wrap(new byte[0]));
    }

    KeyValueContainerData containerData = (KeyValueContainerData) container
        .getContainerData();

    HddsVolume volume = containerData.getVolume();
    VolumeIOStats volumeIOStats = volume.getVolumeIOStats();

    File chunkFile = getChunkFile(containerData, info.getChunkName());

    long len = info.getLen();
    long offset = info.getOffset();
    ByteBuffer data = ByteBuffer.allocate((int) len);
    ChunkUtils.readData(chunkFile, data, offset, len, volumeIOStats);

    return ChunkBuffer.wrap(data);
  }

  @Override
  public void deleteChunk(Container container, BlockID blockID, ChunkInfo info)
      throws StorageContainerException {

    checkLayoutVersion(container);

    Preconditions.checkNotNull(blockID, "Block ID cannot be null.");
    KeyValueContainerData containerData = (KeyValueContainerData) container
        .getContainerData();

    File chunkFile = getChunkFile(containerData, info.getChunkName());

    // if the chunk file does not exist, it might have already been deleted.
    // The call might be because of reapply of transactions on datanode
    // restart.
    if (!chunkFile.exists()) {
      LOG.warn("Chunk file doe not exist. {}", info);
      return;
    }

    long fileLength = chunkFile.length();
    if ((info.getOffset() == 0) && (info.getLen() == fileLength)) {
      FileUtil.fullyDelete(chunkFile);
    } else {
      String msg = String.format(
          "Trying to delete partial chunk %s from file %s with length %s",
          info, chunkFile, fileLength);
      LOG.error(msg);
      throw new StorageContainerException(msg, UNSUPPORTED_REQUEST);
    }
  }

  @Override
  public void finishWriteChunk(KeyValueContainer kvContainer,
      String chunkName) throws IOException {
    File chunkFile = getChunkFile(kvContainer.getContainerData(), chunkName);
    files.close(chunkFile);
  }

  private static File getChunkFile(
      KeyValueContainerData containerData, String chunkName) {
    return new File(containerData.getChunksPath(), chunkName);
  }

  private static final class OpenFiles {

    private final Map<String, OpenFile> files = new HashMap<>();

    public FileChannel getChannel(File file, boolean sync) {
      return files.computeIfAbsent(file.getAbsolutePath(),
          any -> open(file, sync)).getChannel();
    }

    private static OpenFile open(File file, boolean sync) {
      try {
        return new OpenFile(file, sync);
      } catch (FileNotFoundException e) {
        throw new UncheckedIOException(e);
      }
    }

    public void close(File chunkFile) throws IOException {
      OpenFile openFile = files.remove(chunkFile.getAbsolutePath());
      if (openFile != null) {
        LOG.debug("Closing file {}", chunkFile);
        openFile.close();
      } else {
        LOG.debug("File {} not open", chunkFile);
      }
    }
  }

  private static final class OpenFile {

    private final RandomAccessFile file;
    private final Instant openedAt;

    private OpenFile(File file, boolean sync) throws FileNotFoundException {
      String mode = sync ? "rws" : "rw";
      this.file = new RandomAccessFile(file, mode);
      this.openedAt = Instant.now();
    }

    public FileChannel getChannel() {
      return file.getChannel();
    }

    public void close() throws IOException {
      file.close();
    }
  }

}
