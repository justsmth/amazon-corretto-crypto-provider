// Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazon.corretto.crypto.provider;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.security.auth.Destroyable;

/**
 * An auto-closable object designed for use with try-with-resources that automatically zeroizes any
 * registered objects when {@link #close} is called.
 *
 * <p>This class is not thread-safe
 */
class AutoDestroyer implements AutoCloseable {
  // These start null because several of them are usually not used and we'd prefer to avoid
  // allocating unneeded objects.
  private List<byte[]> arraysToClean;
  private List<ByteBuffer> buffersToClean;
  private List<Destroyable> destroyablesToClean;

  /**
   * Registers {@code arr} for zeroization and returns it to the caller. If {@code arr} is null or
   * empty, does not register it.
   *
   * @param arr
   * @return arr
   */
  public byte[] register(final byte[] arr) {
    if (arr != null && arr.length > 0) {
      if (arraysToClean == null) {
        arraysToClean = new ArrayList<>();
      }
      arraysToClean.add(arr);
    }
    return arr;
  }

  /**
   * Registers {@code buff} for zeroization and returns it to the caller. If {@code buff} is null or
   * read-only, does not register it.
   *
   * @param buff
   * @return buff
   */
  public ByteBuffer register(final ByteBuffer buff) {
    if (buff != null && !buff.isReadOnly() && buff.capacity() > 0) {
      if (buffersToClean == null) {
        buffersToClean = new ArrayList<>();
      }
      buffersToClean.add(buff);
    }
    return buff;
  }

  /**
   * Registers {@code des} for zeroization and returns it to the caller. If {@code des} is null does
   * not register it.
   *
   * @param des
   * @return des
   */
  public <D extends Destroyable> D register(final D des) {
    if (des != null) {
      if (destroyablesToClean == null) {
        destroyablesToClean = new ArrayList<>();
      }
      destroyablesToClean.add(des);
    }
    return des;
  }

  @Override
  public void close() {
    if (arraysToClean != null) {
      for (byte[] arr : arraysToClean) {
        Arrays.fill(arr, (byte) 0);
      }
    }
    if (buffersToClean != null) {
      for (ByteBuffer buff : buffersToClean) {
        Utils.zeroByteBuffer(buff);
      }
    }
    if (destroyablesToClean != null) {
      for (Destroyable d : destroyablesToClean) {
        try {
          d.destroy();
        } catch (final Exception ex) {
          // Purposefull ignored. We don't want one failed destruction to block the others.
        }
      }
    }
  }
}
