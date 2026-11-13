/*
 * Copyright LWJGL. All rights reserved. License terms: https://www.lwjgl.org/license
 */
package com.gtnewhorizon.gtnhlib.bytebuf;

import java.nio.ByteBuffer;
import java.util.Objects;


/**
 * Simple index checks.
 *
 * <p>
 * On Java 9 these checks are replaced with the corresponding {@link java.util.Objects} methods, which perform better.
 * </p>
 */
public final class CheckIntrinsics {

    private CheckIntrinsics() {}

    public static int classVersion() {
        return Runtime.version().feature() >= 17 ? 17 : 8;
    }

    public static int checkIndex(int index, int length) {
        if (Runtime.version().feature() >= 9) {
            return Objects.checkIndex(index, length);
        }
        if (index < 0 || length <= index) {
            throw new IndexOutOfBoundsException();
        }
        return index;
    }

    public static int checkFromToIndex(int fromIndex, int toIndex, int length) {
        if (Runtime.version().feature() >= 9) {
            return Objects.checkFromToIndex(fromIndex, toIndex, length);
        }
        if (fromIndex < 0 || toIndex < fromIndex || length < toIndex) {
            throw new IndexOutOfBoundsException();
        }
        return fromIndex;
    }

    public static int checkFromIndexSize(int fromIndex, int size, int length) {
        if (Runtime.version().feature() >= 9) {
            return Objects.checkFromIndexSize(fromIndex, size, length);
        }
        if ((length | fromIndex | size) < 0 || length - fromIndex < size) {
            throw new IndexOutOfBoundsException();
        }
        return fromIndex;
    }

    public static ByteBuffer NewDirectByteBuffer(long address, int capacity) {
        return com.mitchej123.lwjgl.lwjgl2.memory.MemoryUtilities.memByteBuffer(address, capacity);
    }

    public static MemoryUtilities.MemoryAllocator getLwjgl3ifyAllocator() {
        return MemoryManage.getInstance();
    }

}
