package com.gtnewhorizons.angelica.glsm.hooks;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;

import java.util.function.BooleanSupplier;

/** Drains deferred geometry before changing state that is not stored with each batch. */
public final class BatchStateGuard {
    public static Runnable flush;
    /** Host predicate: whether the current draw may be batched, so a program change has to wait. */
    public static volatile BooleanSupplier batchingAllowed = () -> false;
    private static int suspended;

    private BatchStateGuard() {}

    public static void suspend() { suspended++; }
    public static void resume() { suspended--; }
    public static boolean isSuspended() { return suspended != 0; }

    /** Whether the renderer currently in control defers its draws into a batch. */
    public static boolean isBatching() { return batchingAllowed.getAsBoolean(); }

    public static void beforeChange() {
        if (flush == null || suspended != 0 || !GLStateManager.isMainThread()
            || DisplayListManager.getRecordMode() == DisplayListManager.RecordMode.COMPILE) return;
        final CommandRecorder recorder = DisplayListManager.isRecording() ? DisplayListManager.pauseRecording() : null;
        suspend();
        try {
            flush.run();
        } finally {
            resume();
            if (recorder != null) DisplayListManager.resumeRecording(recorder);
        }
    }
}
