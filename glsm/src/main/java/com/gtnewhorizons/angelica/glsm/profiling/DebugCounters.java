package com.gtnewhorizons.angelica.glsm.profiling;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide event counters for work that is only interesting in aggregate: backend transfer and
 * present traffic plus PBR animation updates.
 *
 * <p>The adders stay cumulative so any caller can subtract two {@link Snapshot} values for its own
 * window (the flyby harness accounts per phase this way). The periodic report built by
 * {@link #dumpDeltaAndAdvance()} keeps its own baseline instead of resetting the adders.
 *
 * <p>SDL records transfer, fence and presentation traffic; Iris records PBR animation updates.
 */
public final class DebugCounters {
    public static final LongAdder TRANSFER_WAIT_NANOS = new LongAdder();
    public static final LongAdder FENCE_WAIT_NANOS = new LongAdder();
    public static final LongAdder PRESENTS = new LongAdder();
    public static final LongAdder EMPTY_ACQUIRES = new LongAdder();
    public static final LongAdder PBR_ANIM_UPDATES = new LongAdder();
    public static final LongAdder PBR_ANIM_DRY_RUNS = new LongAdder();

    /** Values already accounted for by the previous periodic report. Render-thread only. */
    private static Snapshot lastReported = Snapshot.ZERO;

    private DebugCounters() {}

    public static Snapshot snapshot() {
        return new Snapshot(
            TRANSFER_WAIT_NANOS.sum(),
            PRESENTS.sum(),
            EMPTY_ACQUIRES.sum(),
            PBR_ANIM_UPDATES.sum(),
            PBR_ANIM_DRY_RUNS.sum(),
            FENCE_WAIT_NANOS.sum());
    }

    /**
     * Counters accumulated since the last {@link #dumpDeltaAndAdvance()} or
     * {@link #advanceReportBaseline()}. Read-only: the baseline stays where it is so a caller can
     * decide whether a report is worth building.
     */
    public static Delta deltaSinceLastReport() {
        return snapshot().since(lastReported);
    }

    /**
     * Whether any counter moved since the last report, without allocating a snapshot. The per-frame
     * report gate calls this on the render thread, so it compares the adders directly.
     */
    public static boolean hasDeltaSinceLastReport() {
        final Snapshot baseline = lastReported;
        return TRANSFER_WAIT_NANOS.sum() != baseline.transferWaitNanos()
            || PRESENTS.sum() != baseline.presents()
            || EMPTY_ACQUIRES.sum() != baseline.emptyAcquires()
            || PBR_ANIM_UPDATES.sum() != baseline.pbrAnimUpdates()
            || PBR_ANIM_DRY_RUNS.sum() != baseline.pbrAnimDryRuns()
            || FENCE_WAIT_NANOS.sum() != baseline.fenceWaitNanos();
    }

    /**
     * Returns the counters accumulated since the previous call and moves the report baseline
     * forward. The adders keep their totals, so accounting based on {@code sum()} is unaffected.
     */
    public static String dumpDeltaAndAdvance() {
        final Snapshot current = snapshot();
        final Delta delta = current.since(lastReported);
        lastReported = current;
        return delta.describe();
    }

    /**
     * Drops everything accumulated so far without reporting it, so a freshly enabled report window
     * does not replay counters collected while nobody was listening. Used when the perf debug toggle
     * resets its own per-window statistics.
     */
    public static void advanceReportBaseline() {
        lastReported = snapshot();
    }

    /** Counter values read at one point in time. */
    public record Snapshot(long transferWaitNanos, long presents, long emptyAcquires, long pbrAnimUpdates, long pbrAnimDryRuns, long fenceWaitNanos) {
        private static final Snapshot ZERO = new Snapshot(0L, 0L, 0L, 0L, 0L, 0L);

        public Delta since(Snapshot start) {
            return new Delta(
                transferWaitNanos - start.transferWaitNanos,
                presents - start.presents,
                emptyAcquires - start.emptyAcquires,
                pbrAnimUpdates - start.pbrAnimUpdates,
                pbrAnimDryRuns - start.pbrAnimDryRuns,
                fenceWaitNanos - start.fenceWaitNanos);
        }
    }

    /** Difference between two snapshots. */
    public record Delta(long transferWaitNanos, long presents, long emptyAcquires, long pbrAnimUpdates, long pbrAnimDryRuns, long fenceWaitNanos) {
        public boolean isEmpty() {
            return transferWaitNanos == 0L
                && presents == 0L
                && emptyAcquires == 0L
                && pbrAnimUpdates == 0L
                && pbrAnimDryRuns == 0L
                && fenceWaitNanos == 0L;
        }

        /** Periodic report entry, listing only the counters that moved. */
        public String describe() {
            final StringBuilder sb = new StringBuilder(96);
            appendEntry(sb, "transferWaitNanos", transferWaitNanos);
            appendEntry(sb, "fenceWaitNanos", fenceWaitNanos);
            appendEntry(sb, "presents", presents);
            appendEntry(sb, "emptyAcquires", emptyAcquires);
            appendEntry(sb, "pbrAnimUpdates", pbrAnimUpdates);
            appendEntry(sb, "pbrAnimDryRuns", pbrAnimDryRuns);
            if (sb.length() == 0) {
                return "";
            }
            return "debugCounters[" + sb + ']';
        }

        private static void appendEntry(StringBuilder sb, String name, long value) {
            if (value == 0L) {
                return;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(name).append('=').append(value);
        }
    }
}
