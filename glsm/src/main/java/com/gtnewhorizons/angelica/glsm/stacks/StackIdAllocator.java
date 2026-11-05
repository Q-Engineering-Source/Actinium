package com.gtnewhorizons.angelica.glsm.stacks;

import java.util.Arrays;

public final class StackIdAllocator {
    private static final int STATIC_BASE = 4096;
    private static final int STATIC_CAPACITY = 16;

    /**
     * One counter per {@link #beginContext()} frame. A context state is built inside a frame, and building one can
     * start another (a drawable binding reached from the first one's initialisation); frame-local counters keep the
     * nested construction from moving the enclosing frame's count, which previously made the second state share the
     * outer bracket and fail the per-context invariant.
     */
    private static int[] frames = new int[4];
    private static int depth;
    private static int perContext = -1;
    private static int staticNext;

    private StackIdAllocator() {}

    public static void beginContext() {
        if (depth == frames.length) {
            frames = Arrays.copyOf(frames, depth * 2);
        }
        frames[depth++] = 0;
    }

    public static void endContext() {
        if (depth == 0) {
            throw new IllegalStateException("endContext without a matching beginContext");
        }
        final int allocated = frames[--depth];
        if (perContext == -1) {
            perContext = allocated;
        } else if (allocated != perContext) {
            throw new IllegalStateException("stack id count changed across contexts: " + perContext + " -> " + allocated);
        }
    }

    public static int nextId() {
        return depth == 0 ? -1 : frames[depth - 1]++;
    }

    public static int nextStaticId() {
        if (staticNext >= STATIC_CAPACITY) {
            throw new IllegalStateException("static stack id capacity exceeded");
        }
        return STATIC_BASE + staticNext++;
    }

    public static int capacity() {
        return STATIC_BASE + STATIC_CAPACITY;
    }
}
