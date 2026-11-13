/*
 * Copyright LWJGL. All rights reserved. License terms: https://www.lwjgl.org/license
 */
package com.gtnewhorizon.gtnhlib.bytebuf;

import com.mitchej123.lwjgl.lwjgl2.memory.MemoryUtilities;

final class MultiReleaseMemCopy {

    private MultiReleaseMemCopy() {}

    public static int classVersion() {
        return Runtime.version().feature() >= 17 ? 17 : 8;
    }

    static void copy(long src, long dst, long bytes) {
        MemoryUtilities.memCopy(src, dst, bytes);
    }

}
