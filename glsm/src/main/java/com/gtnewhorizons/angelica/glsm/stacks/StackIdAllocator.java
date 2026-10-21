package com.gtnewhorizons.angelica.glsm.stacks;

public final class StackIdAllocator {

    private static final ThreadLocal<Integer> next = ThreadLocal.withInitial(() -> -1);
    private static int perContext = -1;

    private StackIdAllocator() {
    }

    public static void beginContext() {
        if (next.get() >= 0) {
            throw new IllegalStateException("Stack id allocation is already active on this thread");
        }
        next.set(0);
    }

    public static void endContext() {
        final int allocated = next.get();
        if (allocated < 0) {
            throw new IllegalStateException("Stack id allocation is not active on this thread");
        }
        try {
            synchronized (StackIdAllocator.class) {
                if (perContext == -1) {
                    perContext = allocated;
                } else if (allocated != perContext) {
                    throw new IllegalStateException("stack id count changed across contexts: " + perContext + " -> " + allocated);
                }
            }
        } finally {
            next.remove();
        }
    }

    /** Abandons an incomplete context construction without poisoning the next allocation pass. */
    public static void abortContext() {
        if (next.get() < 0) {
            throw new IllegalStateException("Stack id allocation is not active on this thread");
        }
        next.remove();
    }

    public static int nextId() {
        final int current = next.get();
        if (current < 0) {
            return -1;
        }
        next.set(current + 1);
        return current;
    }

    public static int allocated() {
        return next.get();
    }
}
