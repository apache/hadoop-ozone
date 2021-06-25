/*
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

package org.apache.hadoop.ozone.recon.codec;

import org.apache.hadoop.hdds.utils.db.IntegerCodec;
import org.apache.hadoop.hdds.utils.db.ShortCodec;
import org.apache.hadoop.ozone.recon.ReconConstants;
import org.apache.hadoop.ozone.recon.api.types.NSSummary;
import org.apache.hadoop.hdds.utils.db.Codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Codec for Namespace Summary.
 */
public class NSSummaryCodec implements Codec<NSSummary> {

  private final Codec<Integer> integerCodec = new IntegerCodec();
  private final Codec<Short> shortCodec = new ShortCodec();
  // 2 int fields + 41-length int array
  private static final int NUM_OF_INTS = 2 + ReconConstants.NUM_OF_BINS;

  @Override
  public byte[] toPersistedFormat(NSSummary object) throws IOException {
    final int sizeOfRes = NUM_OF_INTS * Integer.BYTES + Short.BYTES;
    ByteArrayOutputStream out = new ByteArrayOutputStream(sizeOfRes);
    out.write(integerCodec.toPersistedFormat(object.getNumOfFiles()));
    out.write(integerCodec.toPersistedFormat(object.getSizeOfFiles()));
    out.write(shortCodec.toPersistedFormat((short)ReconConstants.NUM_OF_BINS));
    int[] fileSizeBucket = object.getFileSizeBucket();
    for (int i = 0; i < ReconConstants.NUM_OF_BINS; ++i) {
      out.write(integerCodec.toPersistedFormat(fileSizeBucket[i]));
    }
    return out.toByteArray();
  }

  @Override
  public NSSummary fromPersistedFormat(byte[] rawData) throws IOException {
    assert(rawData.length == NUM_OF_INTS * Integer.BYTES + Short.BYTES);
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawData));
    NSSummary res = new NSSummary();
    res.setNumOfFiles(in.readInt());
    res.setSizeOfFiles(in.readInt());
    short len = in.readShort();
    assert(len == (short) NUM_OF_BINS);
    int[] fileSizeBucket = new int[len];
    for (int i = 0; i < len; ++i) {
      fileSizeBucket[i] = in.readInt();
    }
    res.setFileSizeBucket(fileSizeBucket);
    return res;
  }

  @Override
  public NSSummary copyObject(NSSummary object) {
    NSSummary copy = new NSSummary();
    copy.setNumOfFiles(object.getNumOfFiles());
    copy.setSizeOfFiles(object.getSizeOfFiles());
    copy.setFileSizeBucket(object.getFileSizeBucket());
    return copy;
  }
}
