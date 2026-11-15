package com.dhj.actinium.hud.frame;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTimelineImplTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000 * MS;

    @Test
    void firstFrameOnlyEstablishesTheOrigin() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();

        timeline.onFrame(0L);

        FrameStatistics stats = timeline.snapshot(0L);

        assertTrue(stats.currentFps().isEmpty());
        assertTrue(stats.onePercentLowFps().isEmpty());
    }

    @Test
    void aSingleSampleIsEnoughForTheFrameRate() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        timeline.onFrame(0L);
        timeline.onFrame(10 * MS);

        FrameStatistics stats = timeline.snapshot(10 * MS);

        assertFps(100.0, stats.currentFps());
        assertTrue(stats.onePercentLowFps().isEmpty());
    }

    @Test
    void frameRateAveragesTheSamplesInsideTheLastSecond() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastFast = feed(timeline, 0L, 60, 10 * MS);

        assertFps(100.0, timeline.snapshot(lastFast).currentFps());

        long lastSlow = feed(timeline, lastFast + 10 * MS, 60, 20 * MS);

        assertFps(50.0, timeline.snapshot(lastSlow).currentFps());
    }

    @Test
    void samplesOlderThanTenSecondsLeaveTheLowWindow() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastSlow = feed(timeline, 0L, 5, 2 * SECOND);
        long lastFast = feed(timeline, lastSlow + 2 * SECOND, 1100, 10 * MS);

        FrameStatistics stats = timeline.snapshot(lastFast);

        assertFps(100.0, stats.currentFps());
        assertFps(100.0, stats.onePercentLowFps());
    }

    @Test
    void lowNeedsOneHundredSamples() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastSparse = feed(timeline, 0L, 100, 10 * MS);

        FrameStatistics sparse = timeline.snapshot(lastSparse);

        assertFps(100.0, sparse.currentFps());
        assertTrue(sparse.onePercentLowFps().isEmpty());

        timeline.onFrame(lastSparse + 10 * MS);

        assertFps(100.0, timeline.snapshot(lastSparse + 10 * MS).onePercentLowFps());
    }

    @Test
    void lowAveragesTheTwoWorstSamplesAboveTheHundredSampleThreshold() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastRegular = feed(timeline, 0L, 101, 5 * MS);
        long stall = lastRegular + 50 * MS;

        timeline.onFrame(stall);

        FrameStatistics stats = timeline.snapshot(stall);

        double meanIntervalNanos = (100 * 5.0 + 50.0) * MS / 101;
        double worstMeanNanos = (50.0 + 5.0) * MS / 2;
        assertFps(SECOND / meanIntervalNanos, stats.currentFps());
        assertFps(SECOND / worstMeanNanos, stats.onePercentLowFps());
        assertTrue(stats.onePercentLowFps().getAsDouble() < stats.currentFps().getAsDouble());
    }

    @Test
    void theRingGrowsPastItsInitialCapacityKeepingTheSampleOrder() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastSlow = feed(timeline, 0L, 100, 20 * MS);
        long lastFast = feed(timeline, lastSlow + 20 * MS, 1500, 2 * MS);

        FrameStatistics stats = timeline.snapshot(lastFast);

        assertFps(500.0, stats.currentFps());
        assertFps(50.0, stats.onePercentLowFps());
    }

    @Test
    void snapshotCutsTheWindowsAtTheGivenTime() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastFrame = feed(timeline, 0L, 101, 50 * MS);

        assertFps(20.0, timeline.snapshot(lastFrame).onePercentLowFps());

        FrameStatistics sevenSecondsLater = timeline.snapshot(lastFrame + 7 * SECOND);

        assertTrue(sevenSecondsLater.currentFps().isEmpty());
        assertTrue(sevenSecondsLater.onePercentLowFps().isEmpty());
    }

    @Test
    void resetForgetsTheOriginAndEverySample() {
        FrameTimelineImpl timeline = new FrameTimelineImpl();
        long lastFrame = feed(timeline, 0L, 101, 10 * MS);

        assertFps(100.0, timeline.snapshot(lastFrame).currentFps());

        timeline.reset();

        assertTrue(timeline.snapshot(lastFrame).currentFps().isEmpty());
        assertTrue(timeline.snapshot(lastFrame).onePercentLowFps().isEmpty());

        timeline.onFrame(lastFrame + 10 * MS);
        assertTrue(timeline.snapshot(lastFrame + 10 * MS).currentFps().isEmpty(),
            "the first frame after a reset only establishes the origin");

        timeline.onFrame(lastFrame + 20 * MS);
        assertFps(100.0, timeline.snapshot(lastFrame + 20 * MS).currentFps());
    }

    private static long feed(FrameTimelineImpl timeline, long firstFrameStartNanos, int frameCount, long intervalNanos) {
        long frameStart = firstFrameStartNanos;
        for (int i = 0; i < frameCount; i++) {
            timeline.onFrame(frameStart);
            frameStart += intervalNanos;
        }
        return frameStart - intervalNanos;
    }

    private static void assertFps(double expected, OptionalDouble actual) {
        assertTrue(actual.isPresent(), "expected a frame rate");
        assertEquals(expected, actual.getAsDouble(), 1e-6);
    }
}
