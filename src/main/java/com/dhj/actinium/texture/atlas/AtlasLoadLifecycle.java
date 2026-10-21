package com.dhj.actinium.texture.atlas;

import java.util.Objects;

/** Runs every atlas cleanup action while preserving the exception that caused cleanup to start. */
public final class AtlasLoadLifecycle {

    private AtlasLoadLifecycle() {
    }

    /**
     * Runs all actions in order. A cleanup failure becomes the result when no earlier failure exists; otherwise it
     * is suppressed onto the earlier failure and cleanup continues.
     */
    public static Throwable runCleanup(Throwable primaryFailure, Runnable... actions) {
        Throwable failure = primaryFailure;
        for (Runnable action : actions) {
            Objects.requireNonNull(action, "cleanup action");
            try {
                action.run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        return failure;
    }

    /** Rethrows a failure returned by {@link #runCleanup} without changing unchecked exception types. */
    public static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Atlas cleanup failed", failure);
    }
}
