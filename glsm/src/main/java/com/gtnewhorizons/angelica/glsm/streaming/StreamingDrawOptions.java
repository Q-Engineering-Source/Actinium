package com.gtnewhorizons.angelica.glsm.streaming;

/**
 * Per-thread options for streaming draws issued by external shader renderers.
 *
 * <p>External programs already own their vertex attributes and uniforms. The scope lets a
 * caller retain that program while Actinium still performs the streaming upload and draw.</p>
 */
public final class StreamingDrawOptions {
    private static final ThreadLocal<Integer> EXTERNAL_PROGRAM_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private StreamingDrawOptions() {
    }

    /** Preserves the caller's currently bound shader program for draws in this scope. */
    public static Scope preserveExternalProgram() {
        EXTERNAL_PROGRAM_DEPTH.set(EXTERNAL_PROGRAM_DEPTH.get() + 1);
        return new Scope();
    }

    /** Returns whether the current thread has requested external program preservation. */
    public static boolean isExternalProgramPreserved() {
        return EXTERNAL_PROGRAM_DEPTH.get() > 0;
    }

    /** Restores the previous streaming draw option when closed. */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {
        }

        @Override
        public void close() {
            if (closed) {
                throw new IllegalStateException("Streaming draw scope already closed");
            }
            closed = true;
            int depth = EXTERNAL_PROGRAM_DEPTH.get();
            if (depth <= 0) {
                throw new IllegalStateException("Streaming draw scope depth underflow");
            }
            if (depth == 1) {
                EXTERNAL_PROGRAM_DEPTH.remove();
            } else {
                EXTERNAL_PROGRAM_DEPTH.set(depth - 1);
            }
        }
    }
}
