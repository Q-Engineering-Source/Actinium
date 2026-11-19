package com.dhj.actinium.hud.metric;

import com.dhj.actinium.hud.frame.FrameStatistics;
import com.dhj.actinium.hud.frame.FrameTimeline;
import com.dhj.actinium.hud.system.SystemMetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorMetricsImplTest {
    private static final long MS = 1_000_000L;
    private static final long MIB = 1024L * 1024L;
    private static final int REFRESH_MILLIS = 500;
    private static final Object WORLD = new Object();

    private final StubFrameTimeline timeline = new StubFrameTimeline();
    private final IndicatorMetrics metrics = new IndicatorMetricsImpl(this.timeline);

    @Test
    void aSwitchedOffIndicatorNeitherSamplesFramesNorReturnsValues() {
        this.metrics.onFrameStart(0L, WORLD, false);

        IndicatorSnapshot snapshot =
            this.metrics.onFrameEnd(10 * MS, WORLD, false, REFRESH_MILLIS, memoryUsed(2048 * MIB));

        assertEquals(IndicatorSnapshot.empty(), snapshot);
        assertTrue(this.timeline.frameStarts.isEmpty());
        assertEquals(0, this.timeline.snapshotCount);
    }

    @Test
    void aMissingWorldTokenCountsAsInactive() {
        this.metrics.onFrameStart(0L, null, true);

        IndicatorSnapshot snapshot =
            this.metrics.onFrameEnd(10 * MS, null, true, REFRESH_MILLIS, memoryUsed(2048 * MIB));

        assertEquals(IndicatorSnapshot.empty(), snapshot);
        assertTrue(this.timeline.frameStarts.isEmpty());
    }

    @Test
    void theFrameStartsFeedTheTimelineAndTheFrameEndIsSnapshotted() {
        this.metrics.onFrameStart(0L, WORLD, true);
        this.metrics.onFrameStart(5 * MS, WORLD, true);

        this.metrics.onFrameEnd(6 * MS, WORLD, true, REFRESH_MILLIS, memoryUsed(0));

        assertEquals(List.of(0L, 5 * MS), this.timeline.frameStarts);
        assertEquals(6 * MS, this.timeline.lastSnapshotNanos);
    }

    @Test
    void aCallInsideTheRefreshIntervalReturnsTheLastSnapshot() {
        this.timeline.report(120.0, 60.0);
        this.metrics.onFrameStart(0L, WORLD, true);
        IndicatorSnapshot first = this.metrics.onFrameEnd(0L, WORLD, true, REFRESH_MILLIS, memoryUsed(1024 * MIB));

        this.timeline.report(30.0, 15.0);
        IndicatorSnapshot repeated =
            this.metrics.onFrameEnd(REFRESH_MILLIS * MS - 1, WORLD, true, REFRESH_MILLIS, memoryUsed(4096 * MIB));

        assertSame(first, repeated);
        assertEquals(1, this.timeline.snapshotCount);
        assertEquals(120.0, repeated.fps().current().getAsDouble());
        assertEquals(1024.0 * MIB, repeated.memory().current().getAsDouble());
        assertEquals(1024.0 * MIB, repeated.memory().max().getAsDouble());
    }

    @Test
    void theNextRefreshIntervalPublishesAndWidensTheRanges() {
        this.timeline.report(120.0, 60.0);
        this.metrics.onFrameStart(0L, WORLD, true);
        this.metrics.onFrameEnd(0L, WORLD, true, REFRESH_MILLIS, memoryUsed(1024 * MIB));

        this.timeline.report(30.0, 15.0);
        IndicatorSnapshot published =
            this.metrics.onFrameEnd(REFRESH_MILLIS * MS, WORLD, true, REFRESH_MILLIS, memoryUsed(4096 * MIB));

        assertEquals(30.0, published.fps().current().getAsDouble());
        assertEquals(30.0, published.fps().min().getAsDouble());
        assertEquals(120.0, published.fps().max().getAsDouble());
        assertEquals(15.0, published.fpsLow().current().getAsDouble());
        assertEquals(60.0, published.fpsLow().max().getAsDouble());
        assertEquals(4096.0 * MIB, published.memory().current().getAsDouble());
        assertEquals(1024.0 * MIB, published.memory().min().getAsDouble());
        assertEquals(2, this.timeline.snapshotCount);
    }

    @Test
    void aNewWorldTokenDropsTheSamplesTheSnapshotAndTheRanges() {
        this.timeline.report(120.0, 60.0);
        this.metrics.onFrameStart(0L, WORLD, true);
        this.metrics.onFrameStart(10 * MS, WORLD, true);
        this.metrics.onFrameEnd(10 * MS, WORLD, true, REFRESH_MILLIS, memoryUsed(8192 * MIB));

        Object otherWorld = new Object();
        this.timeline.report(30.0, 15.0);
        this.metrics.onFrameStart(20 * MS, otherWorld, true);
        IndicatorSnapshot published =
            this.metrics.onFrameEnd(20 * MS, otherWorld, true, REFRESH_MILLIS, memoryUsed(1024 * MIB));

        assertEquals(List.of(20 * MS), this.timeline.frameStarts);
        assertEquals(30.0, published.fps().current().getAsDouble());
        assertEquals(30.0, published.fps().min().getAsDouble());
        assertEquals(30.0, published.fps().max().getAsDouble());
        assertEquals(1024.0 * MIB, published.memory().max().getAsDouble());
    }

    @Test
    void anEqualWorldTokenKeepsTheSamples() {
        String overworld = new String("overworld");
        this.metrics.onFrameStart(0L, overworld, true);
        int resetsOfActivation = this.timeline.resetCount;

        this.metrics.onFrameStart(10 * MS, new String("overworld"), true);

        assertEquals(List.of(0L, 10 * MS), this.timeline.frameStarts);
        assertEquals(resetsOfActivation, this.timeline.resetCount);
    }

    @Test
    void switchingTheIndicatorOffAndOnStartsTheStatisticsOver() {
        this.timeline.report(120.0, 60.0);
        this.metrics.onFrameStart(0L, WORLD, true);
        this.metrics.onFrameEnd(0L, WORLD, true, REFRESH_MILLIS, memoryUsed(8192 * MIB));

        IndicatorSnapshot off = this.metrics.onFrameEnd(MS, WORLD, false, REFRESH_MILLIS, memoryUsed(64 * MIB));

        assertEquals(IndicatorSnapshot.empty(), off);

        this.timeline.report(30.0, 15.0);
        this.metrics.onFrameStart(2 * MS, WORLD, true);
        IndicatorSnapshot back =
            this.metrics.onFrameEnd(2 * MS, WORLD, true, REFRESH_MILLIS, memoryUsed(1024 * MIB));

        assertEquals(List.of(2 * MS), this.timeline.frameStarts);
        assertEquals(30.0, back.fps().min().getAsDouble());
        assertEquals(30.0, back.fps().max().getAsDouble());
        assertEquals(1024.0 * MIB, back.memory().min().getAsDouble());
        assertEquals(1024.0 * MIB, back.memory().max().getAsDouble());
    }

    @Test
    void emptyMeasurementsStayOutOfTheRanges() {
        this.metrics.onFrameStart(0L, WORLD, true);
        IndicatorSnapshot blank =
            this.metrics.onFrameEnd(0L, WORLD, true, REFRESH_MILLIS, SystemMetricsSnapshot.empty());

        assertTrue(blank.fps().current().isEmpty());
        assertTrue(blank.fps().min().isEmpty());
        assertTrue(blank.fpsLow().max().isEmpty());
        assertTrue(blank.memory().min().isEmpty());

        this.timeline.report(60.0, 30.0);
        IndicatorSnapshot seeded =
            this.metrics.onFrameEnd(REFRESH_MILLIS * MS, WORLD, true, REFRESH_MILLIS, memoryUsed(1024 * MIB));

        assertEquals(60.0, seeded.fps().min().getAsDouble());
        assertEquals(1024.0 * MIB, seeded.memory().min().getAsDouble());

        this.timeline.reportEmpty();
        IndicatorSnapshot skipped = this.metrics.onFrameEnd(2L * REFRESH_MILLIS * MS, WORLD, true, REFRESH_MILLIS,
            SystemMetricsSnapshot.empty());

        assertTrue(skipped.fps().current().isEmpty());
        assertEquals(60.0, skipped.fps().min().getAsDouble());
        assertEquals(60.0, skipped.fps().max().getAsDouble());
        assertEquals(1024.0 * MIB, skipped.memory().min().getAsDouble());
        assertEquals(1024.0 * MIB, skipped.memory().max().getAsDouble());

        this.timeline.report(45.0, 20.0);
        IndicatorSnapshot later = this.metrics.onFrameEnd(3L * REFRESH_MILLIS * MS, WORLD, true, REFRESH_MILLIS,
            memoryUsed(2048 * MIB));

        assertEquals(45.0, later.fps().min().getAsDouble());
        assertEquals(60.0, later.fps().max().getAsDouble());
        assertEquals(1024.0 * MIB, later.memory().min().getAsDouble());
        assertEquals(2048.0 * MIB, later.memory().max().getAsDouble());
    }

    @Test
    void theRamRangeFollowsThePublishedHistory() {
        this.metrics.onFrameStart(0L, WORLD, true);
        this.metrics.onFrameEnd(0L, WORLD, true, REFRESH_MILLIS, memoryUsed(8192 * MIB));
        this.metrics.onFrameEnd(REFRESH_MILLIS * MS, WORLD, true, REFRESH_MILLIS, memoryUsed(12288 * MIB));

        SystemMetricsSnapshot latestSystem = memoryUsed(4096 * MIB);
        IndicatorSnapshot published =
            this.metrics.onFrameEnd(2L * REFRESH_MILLIS * MS, WORLD, true, REFRESH_MILLIS, latestSystem);

        assertEquals(4096.0 * MIB, published.memory().current().getAsDouble());
        assertEquals(4096.0 * MIB, published.memory().min().getAsDouble());
        assertEquals(12288.0 * MIB, published.memory().max().getAsDouble());
        assertSame(latestSystem, published.system());
    }

    @Test
    void aNonPositiveRefreshIntervalIsRejected() {
        this.metrics.onFrameStart(0L, WORLD, true);

        assertThrows(IllegalArgumentException.class,
            () -> this.metrics.onFrameEnd(0L, WORLD, true, 0, SystemMetricsSnapshot.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> this.metrics.onFrameEnd(0L, WORLD, true, -REFRESH_MILLIS, SystemMetricsSnapshot.empty()));
    }

    @Test
    void aMissingSystemSnapshotIsRejected() {
        this.metrics.onFrameStart(0L, WORLD, true);

        assertThrows(NullPointerException.class,
            () -> this.metrics.onFrameEnd(0L, WORLD, true, REFRESH_MILLIS, null));
    }

    private static SystemMetricsSnapshot memoryUsed(double usedBytes) {
        return new SystemMetricsSnapshot(
            OptionalDouble.of(usedBytes),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty());
    }

    private static final class StubFrameTimeline implements FrameTimeline {
        private final List<Long> frameStarts = new ArrayList<>();
        private FrameStatistics statistics = FrameStatistics.empty();
        private int snapshotCount;
        private int resetCount;
        private long lastSnapshotNanos;

        void report(double currentFps, double onePercentLowFps) {
            this.statistics = new FrameStatistics(OptionalDouble.of(currentFps), OptionalDouble.of(onePercentLowFps));
        }

        void reportEmpty() {
            this.statistics = FrameStatistics.empty();
        }

        @Override
        public void onFrame(long frameStartNanos) {
            this.frameStarts.add(frameStartNanos);
        }

        @Override
        public FrameStatistics snapshot(long nowNanos) {
            this.snapshotCount++;
            this.lastSnapshotNanos = nowNanos;
            return this.statistics;
        }

        @Override
        public void reset() {
            this.resetCount++;
            this.frameStarts.clear();
        }
    }
}
