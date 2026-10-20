package com.gtnewhorizons.angelica.glsm.shader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The saved-program cache reports what it did, so a pack load's log says whether the programs came back
 * from disk or were compiled again. The counters themselves are what this covers: the hit and restore
 * paths need a live GL context, so the reporting is driven through the miss-build entry point the loaders
 * call and through the formatting the reports use.
 */
class ProgramBinaryCacheStatsTest {

    @BeforeEach
    void resetCounters() {
        ProgramBinaryCache.deleteAll();
    }

    @Test
    void intervalReportStaysEmptyWhileTheCacheIsUntouched() {
        assertEquals("", ProgramBinaryCache.statsSummary());
    }

    @Test
    void intervalReportPricesTheMissBuildsAndClearsThem() {
        ProgramBinaryCache.recordMissBuild(2_000_000L);
        ProgramBinaryCache.recordMissBuild(4_000_000L);

        assertEquals("progCache[hit=0,miss=0,hitRate=n/a,save=0,reject=0,builds=2,restoreMs=0.000,missBuildMs=6.000]",
            ProgramBinaryCache.statsSummary());
        assertEquals("", ProgramBinaryCache.statsSummary(), "the report must clear what it reported");
    }

    @Test
    void epochSummaryPricesTheHitsWithTheSessionAverageMissBuild() {
        ProgramBinaryCache.recordMissBuild(2_000_000L);
        ProgramBinaryCache.recordMissBuild(4_000_000L);

        final String summary = ProgramBinaryCache.dumpEventSummary();

        assertEquals(
            "progCache[hit=0,miss=0,hitRate=n/a,save=0,reject=0,builds=2,restoreMs=0.000,missBuildMs=6.000,"
                + "avgMissBuildMs=3.000,estSavedCompileMs=0.000,sessionMissBuilds=2]",
            summary);
        assertEquals(
            "progCache[hit=0,miss=0,hitRate=n/a,save=0,reject=0,builds=0,restoreMs=0.000,missBuildMs=0.000,"
                + "avgMissBuildMs=3.000,estSavedCompileMs=0.000,sessionMissBuilds=2]",
            ProgramBinaryCache.dumpEventSummary(),
            "the epoch counters reset while the session average the estimate is priced from survives");
    }

    @Test
    void reportsTheHitRateAndTheCompileTimeTheHitsSaved() {
        final String line = ProgramBinaryCache.describeEpoch(
            new ProgramBinaryCache.Snapshot(6L, 2L, 8L, 1L, 2L, 30_000_000L, 8_000_000L), 2L, 8_000_000L);

        assertEquals("hit=6,miss=2,hitRate=75.0%,save=8,reject=1,builds=2,restoreMs=30.000,missBuildMs=8.000,"
            + "avgMissBuildMs=4.000,estSavedCompileMs=24.000,sessionMissBuilds=2", line);
    }

    @Test
    void reportsNoEstimateBeforeAnyBuildWasTimed() {
        final String line = ProgramBinaryCache.describeEpoch(
            new ProgramBinaryCache.Snapshot(3L, 0L, 0L, 0L, 0L, 1_000_000L, 0L), 0L, 0L);

        assertEquals("hit=3,miss=0,hitRate=100.0%,save=0,reject=0,builds=0,restoreMs=1.000,missBuildMs=0.000,"
            + "avgMissBuildMs=n/a,estSavedCompileMs=n/a,sessionMissBuilds=0", line);
    }
}
