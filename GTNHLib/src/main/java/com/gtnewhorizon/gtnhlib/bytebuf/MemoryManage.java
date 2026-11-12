/*
 * Copyright LWJGL. All rights reserved. License terms: https://www.lwjgl.org/license
 */
package com.gtnewhorizon.gtnhlib.bytebuf;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.MemoryAllocator;

import com.mitchej123.lwjgl.lwjgl2.memory.MemoryUtilities;

/** Provides {@link MemoryAllocator} implementations for {@link MemoryUtilities} to use. */
final class MemoryManage {

    private MemoryManage() {}

    static MemoryAllocator getInstance() {
        return new LwjglAllocator();
    }

    /** Adapts LWJGL's maintained native allocator to the local public allocator contract. */
    private static final class LwjglAllocator implements MemoryAllocator {

        @Override
        public long malloc(long size) {
            return MemoryUtilities.nmemAlloc(size);
        }

        @Override
        public long calloc(long num, long size) {
            return MemoryUtilities.nmemCalloc(1L, Math.multiplyExact(num, size));
        }

        @Override
        public long realloc(long ptr, long size) {
            return MemoryUtilities.nmemRealloc(ptr, size);
        }

        @Override
        public void free(long ptr) {
            MemoryUtilities.nmemFree(ptr);
        }
    }

}
