package com.dhj.actinium.render.vertex;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Resolves the native memory address of direct {@link ByteBuffer} instances and holds the shared
 * {@code Unsafe} for the vertex package. The vertex write hot path performs absolute stores
 * straight into the staging buffer of {@code BufferBuilder} to avoid the per-call index and
 * bounds work of {@code ByteBuffer.putXxx}; the address comes from GTNHLib
 * {@code MemoryUtilities#memAddress0}. The single reflective lookup of
 * {@code sun.misc.Unsafe#theUnsafe} remains because no in-repo facility exposes an
 * {@code Unsafe} instance and the FFM API is unavailable under {@code --release 21}; all raw
 * stores of the vertex writers route through this holder so {@code sun.misc.Unsafe} is
 * referenced from exactly one place.
 */
public final class DirectBufferAddress {
    /** Shared {@code Unsafe} instance consumed by the vertex writer singletons. */
    static final Unsafe UNSAFE;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError("sun.misc.Unsafe is not reachable: " + e);
        }
    }

    private DirectBufferAddress() {
    }

    /**
     * Returns the native address backing the given direct buffer, or fails fast when the
     * buffer is not direct or its address has not been assigned yet.
     *
     * @param buffer direct buffer whose native address is requested
     * @return native base address of the buffer contents
     */
    public static long of(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Vertex staging buffer must be direct");
        }
        long address = MemoryUtilities.memAddress0(buffer);
        if (address == 0) {
            throw new IllegalStateException("Direct vertex staging buffer has no native address");
        }
        return address;
    }
}
