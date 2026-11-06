package com.gtnewhorizons.angelica.glsm.profiling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugCountersTest {

    @Test
    void reportsOnlyTheTrafficBetweenTwoSnapshots() {
        final DebugCounters.Snapshot start = DebugCounters.snapshot();

        DebugCounters.PBR_ANIM_UPDATES.add(7L);
        DebugCounters.PBR_ANIM_DRY_RUNS.add(3L);

        final DebugCounters.Delta delta = DebugCounters.snapshot().since(start);

        assertEquals(7L, delta.pbrAnimUpdates());
        assertEquals(3L, delta.pbrAnimDryRuns());
        assertEquals(0L, delta.transferWaitNanos());
        assertEquals(0L, delta.presents());
        assertEquals(0L, delta.emptyAcquires());
        assertFalse(delta.isEmpty());
    }

    @Test
    void keepsSumsCumulativeAcrossSuccessiveSnapshots() {
        final DebugCounters.Snapshot start = DebugCounters.snapshot();

        DebugCounters.TRANSFER_WAIT_NANOS.add(1_000L);
        final DebugCounters.Snapshot afterFirstSample = DebugCounters.snapshot();
        DebugCounters.TRANSFER_WAIT_NANOS.add(500L);
        final DebugCounters.Snapshot afterSecondSample = DebugCounters.snapshot();

        assertEquals(1_000L, afterFirstSample.since(start).transferWaitNanos());
        assertEquals(1_500L, afterSecondSample.since(start).transferWaitNanos());
        assertEquals(500L, afterSecondSample.since(afterFirstSample).transferWaitNanos());
    }

    @Test
    void dumpReportsTheWindowAndKeepsCumulativeSums() {
        DebugCounters.advanceReportBaseline();
        final long updatesBefore = DebugCounters.PBR_ANIM_UPDATES.sum();
        DebugCounters.PBR_ANIM_UPDATES.add(4L);

        assertEquals("debugCounters[pbrAnimUpdates=4]", DebugCounters.dumpDeltaAndAdvance());
        assertEquals(updatesBefore + 4L, DebugCounters.PBR_ANIM_UPDATES.sum());
        assertEquals("", DebugCounters.dumpDeltaAndAdvance());
    }

    @Test
    void advanceReportBaselineDropsCountersCollectedBeforeIt() {
        DebugCounters.PBR_ANIM_DRY_RUNS.add(9L);
        DebugCounters.advanceReportBaseline();
        DebugCounters.PBR_ANIM_DRY_RUNS.add(2L);

        assertEquals("debugCounters[pbrAnimDryRuns=2]", DebugCounters.dumpDeltaAndAdvance());
    }

    @Test
    void describeOmitsCountersThatDidNotMove() {
        final DebugCounters.Delta delta = new DebugCounters.Delta(0L, 0L, 0L, 12L, 5L);

        assertEquals("debugCounters[pbrAnimUpdates=12,pbrAnimDryRuns=5]", delta.describe());
    }

    @Test
    void emptyDeltaDescribesNothing() {
        final DebugCounters.Delta delta = new DebugCounters.Delta(0L, 0L, 0L, 0L, 0L);

        assertTrue(delta.isEmpty());
        assertEquals("", delta.describe());
    }
}
