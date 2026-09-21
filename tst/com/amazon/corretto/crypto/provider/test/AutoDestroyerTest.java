// Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazon.corretto.crypto.provider.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import javax.security.auth.Destroyable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Tests for {@code AutoDestroyer}.
 *
 * <p>{@code AutoDestroyer} is package-private; this test reaches it via reflection to avoid
 * widening visibility for tests alone.
 */
@ExtendWith(TestResultLogger.class)
@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = TestUtil.RESOURCE_GLOBAL, mode = ResourceAccessMode.READ)
public class AutoDestroyerTest {
  private static Constructor<?> ctor;
  private static Method registerArray;
  private static Method registerBuffer;
  private static Method registerDestroyable;

  @BeforeAll
  public static void setUp() throws Exception {
    // Force provider load so the package is initialized.
    AmazonCorrettoCryptoProvider.INSTANCE.getName();
    final Class<?> clazz = Class.forName("com.amazon.corretto.crypto.provider.AutoDestroyer");
    ctor = clazz.getDeclaredConstructor();
    ctor.setAccessible(true);
    registerArray = clazz.getDeclaredMethod("register", byte[].class);
    registerArray.setAccessible(true);
    registerBuffer = clazz.getDeclaredMethod("register", ByteBuffer.class);
    registerBuffer.setAccessible(true);
    registerDestroyable = clazz.getDeclaredMethod("register", Destroyable.class);
    registerDestroyable.setAccessible(true);
  }

  private static AutoCloseable newDestroyer() throws Exception {
    return (AutoCloseable) ctor.newInstance();
  }

  @SuppressWarnings("unchecked")
  private static <T> T invoke(final Method method, final Object receiver, final Object arg)
      throws Exception {
    try {
      return (T) method.invoke(receiver, arg);
    } catch (final InvocationTargetException ex) {
      if (ex.getCause() instanceof Exception) {
        throw (Exception) ex.getCause();
      }
      throw ex;
    }
  }

  private static byte[] registerArray(final AutoCloseable destroyer, final byte[] arr)
      throws Exception {
    return invoke(registerArray, destroyer, arr);
  }

  private static ByteBuffer registerBuffer(final AutoCloseable destroyer, final ByteBuffer buff)
      throws Exception {
    return invoke(registerBuffer, destroyer, buff);
  }

  private static Destroyable registerDestroyable(
      final AutoCloseable destroyer, final Destroyable des) throws Exception {
    return invoke(registerDestroyable, destroyer, des);
  }

  private static final class RecordingDestroyable implements Destroyable {
    private boolean destroyed = false;
    private final boolean throwOnDestroy;

    RecordingDestroyable() {
      this(false);
    }

    RecordingDestroyable(final boolean throwOnDestroy) {
      this.throwOnDestroy = throwOnDestroy;
    }

    @Override
    public void destroy() {
      destroyed = true;
      if (throwOnDestroy) {
        throw new RuntimeException("boom");
      }
    }

    @Override
    public boolean isDestroyed() {
      return destroyed;
    }
  }

  @Test
  public void closeWithNothingRegistered_doesNotThrow() throws Exception {
    try (AutoCloseable destroyer = newDestroyer()) {
      // Nothing registered.
    }
  }

  @Test
  public void registerArray_returnsSameReference() throws Exception {
    final byte[] arr = {1, 2, 3, 4, 5};
    try (AutoCloseable destroyer = newDestroyer()) {
      assertSame(arr, registerArray(destroyer, arr));
    }
  }

  @Test
  public void registerArray_zeroedOnClose() throws Exception {
    final byte[] arr = {1, 2, 3, 4, 5};
    try (AutoCloseable destroyer = newDestroyer()) {
      registerArray(destroyer, arr);
    }
    assertArrayEquals(new byte[5], arr);
  }

  @Test
  public void registerArray_null_doesNotThrow() throws Exception {
    try (AutoCloseable destroyer = newDestroyer()) {
      assertDoesNotThrow(() -> registerArray(destroyer, null));
    }
  }

  @Test
  public void registerArray_empty_doesNotThrow() throws Exception {
    final byte[] empty = new byte[0];
    try (AutoCloseable destroyer = newDestroyer()) {
      registerArray(destroyer, empty);
    }
  }

  @Test
  public void registerArray_multipleArrays_allZeroedOnClose() throws Exception {
    final byte[] arr1 = {1, 2, 3};
    final byte[] arr2 = {4, 5, 6};
    try (AutoCloseable destroyer = newDestroyer()) {
      registerArray(destroyer, arr1);
      registerArray(destroyer, arr2);
    }
    assertArrayEquals(new byte[3], arr1);
    assertArrayEquals(new byte[3], arr2);
  }

  @Test
  public void registerBuffer_returnsSameReference() throws Exception {
    final ByteBuffer buff = ByteBuffer.allocate(16);
    try (AutoCloseable destroyer = newDestroyer()) {
      assertSame(buff, registerBuffer(destroyer, buff));
    }
  }

  @Test
  public void registerBuffer_zeroesEntireCapacityOnClose() throws Exception {
    final ByteBuffer buff = ByteBuffer.allocate(16);
    for (int i = 0; i < buff.capacity(); i++) {
      buff.put(i, (byte) 0xab);
    }
    // Narrow the visible window to confirm the whole backing capacity is zeroed, not just
    // the remaining/position-to-limit window.
    buff.position(4);
    buff.limit(8);

    try (AutoCloseable destroyer = newDestroyer()) {
      registerBuffer(destroyer, buff);
    }

    final ByteBuffer view = buff.duplicate();
    view.clear();
    for (int i = 0; i < view.capacity(); i++) {
      assertTrue(view.get(i) == 0, "byte " + i + " was not zeroed");
    }
  }

  @Test
  public void registerBuffer_readOnly_notZeroedAndNoException() throws Exception {
    final ByteBuffer buff = ByteBuffer.allocate(16);
    for (int i = 0; i < buff.capacity(); i++) {
      buff.put(i, (byte) 0xab);
    }
    final ByteBuffer readOnly = buff.asReadOnlyBuffer();

    try (AutoCloseable destroyer = newDestroyer()) {
      assertDoesNotThrow(() -> registerBuffer(destroyer, readOnly));
    }

    for (int i = 0; i < buff.capacity(); i++) {
      assertTrue(buff.get(i) == (byte) 0xab);
    }
  }

  @Test
  public void registerBuffer_null_doesNotThrow() throws Exception {
    try (AutoCloseable destroyer = newDestroyer()) {
      assertDoesNotThrow(() -> registerBuffer(destroyer, null));
    }
  }

  @Test
  public void registerBuffer_zeroCapacity_doesNotThrow() throws Exception {
    final ByteBuffer empty = ByteBuffer.allocate(0);
    try (AutoCloseable destroyer = newDestroyer()) {
      registerBuffer(destroyer, empty);
    }
  }

  @Test
  public void registerDestroyable_returnsSameReference() throws Exception {
    final RecordingDestroyable des = new RecordingDestroyable();
    try (AutoCloseable destroyer = newDestroyer()) {
      assertSame(des, registerDestroyable(destroyer, des));
    }
  }

  @Test
  public void registerDestroyable_destroyedOnClose() throws Exception {
    final RecordingDestroyable des = new RecordingDestroyable();
    try (AutoCloseable destroyer = newDestroyer()) {
      registerDestroyable(destroyer, des);
    }
    assertTrue(des.isDestroyed());
  }

  @Test
  public void registerDestroyable_null_doesNotThrow() throws Exception {
    try (AutoCloseable destroyer = newDestroyer()) {
      assertDoesNotThrow(() -> registerDestroyable(destroyer, null));
    }
  }

  @Test
  public void registerDestroyable_throwingDestroy_doesNotPreventOthersOrPropagate()
      throws Exception {
    final RecordingDestroyable throwing = new RecordingDestroyable(true);
    final RecordingDestroyable normal = new RecordingDestroyable(false);
    try (AutoCloseable destroyer = newDestroyer()) {
      registerDestroyable(destroyer, throwing);
      registerDestroyable(destroyer, normal);
    }
    assertTrue(throwing.isDestroyed());
    assertTrue(normal.isDestroyed());
  }

  @Test
  public void whenExceptionThrownInsideTryBlock_registeredArrayIsStillZeroed() throws Exception {
    final byte[] arr = {1, 2, 3, 4};
    final RuntimeException boom = new RuntimeException("boom");

    final RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> {
              try (AutoCloseable destroyer = newDestroyer()) {
                registerArray(destroyer, arr);
                throw boom;
              }
            });

    assertSame(boom, thrown);
    assertArrayEquals(new byte[4], arr);
  }

  @Test
  public void closingTwice_doesNotThrow() throws Exception {
    final RecordingDestroyable des = new RecordingDestroyable();
    final AutoCloseable destroyer = newDestroyer();
    registerDestroyable(destroyer, des);
    destroyer.close();
    assertDoesNotThrow(destroyer::close);
    assertTrue(des.isDestroyed());
  }

  @Test
  public void multipleTypesAllZeroed() throws Exception {
    final byte[] arr = {1, 2, 3, 4};
    final ByteBuffer buff = ByteBuffer.allocate(16);
    for (int i = 0; i < buff.capacity(); i++) {
      buff.put(i, (byte) 0xab);
    }
    // Narrow the visible window to confirm the whole backing capacity is zeroed, not just
    // the remaining/position-to-limit window.
    buff.position(4);
    buff.limit(8);
    final RecordingDestroyable des = new RecordingDestroyable();

    try (AutoCloseable destroyer = newDestroyer()) {
      registerArray(destroyer, arr);
      registerBuffer(destroyer, buff);
      registerDestroyable(destroyer, des);
    }

    assertArrayEquals(new byte[arr.length], arr);
    final ByteBuffer view = buff.duplicate();
    view.clear();
    for (int i = 0; i < view.capacity(); i++) {
      assertTrue(view.get(i) == 0, "byte " + i + " was not zeroed");
    }
    assertTrue(des.isDestroyed());
  }
}
