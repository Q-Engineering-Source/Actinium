package com.gtnewhorizon.gtnhlib.bytebuf;

import com.mitchej123.lwjgl.lwjgl2.memory.MemoryUtilities;

import java.nio.ByteBuffer;

/**
 * String decoding utilities.
 *
 * <p>
 * On Java 9 different implementations are used that work better with compat strings (JEP 254).
 * </p>
 */
final class MultiReleaseTextDecoding {

    private MultiReleaseTextDecoding() {}

    public static int classVersion() {
        return Runtime.version().feature() >= 17 ? 17 : 8;
    }

    /** @see MemoryUtilities#memUTF8(ByteBuffer, int, int) */
    static String decodeUTF8(long source, int length) {
        return MemoryUtilities.memUTF8(source, length);
    }
}
