package com.dhj.actinium.hud.metric;

import com.dhj.actinium.hud.frame.FrameStatistics;
import com.dhj.actinium.hud.frame.FrameTimeline;
import com.dhj.actinium.hud.system.SystemMetricsSnapshot;

import java.util.Objects;

/**
 * Implementation of {@link IndicatorMetrics} on top of the injected {@link FrameTimeline}.
 *
 * <p>The timeline owns the frame samples, so the world change and the activity change only have to
 * reset it next to the published statistics they belong to. The hardware values are not accumulated:
 * the snapshot keeps the ones sampled for the published frame, while the used memory enters the
 * memory range at every publish.
 */
public final class IndicatorMetricsImpl implements IndicatorMetrics {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final FrameTimeline timeline;

    private Object worldToken;
    private boolean active;

    private MetricStatistic fps = MetricStatistic.empty();
    private MetricStatistic fpsLow = MetricStatistic.empty();
    private MetricStatistic memory = MetricStatistic.empty();
    private IndicatorSnapshot latest = IndicatorSnapshot.empty();
    private long lastPublishNanos;
    private boolean published;

    /**
     * Creates the statistics of one indicator.
     *
     * @param timeline timeline the frame starts are recorded into
     */
    public IndicatorMetricsImpl(FrameTimeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public void onFrameStart(long startNanos, Object worldToken, boolean enabled) {
        updateActivation(worldToken, enabled && worldToken != null);
        if (this.active) {
            this.timeline.onFrame(startNanos);
        }
    }

    @Override
    public IndicatorSnapshot onFrameEnd(long endNanos, Object worldToken, boolean enabled,
                                        int refreshIntervalMillis, SystemMetricsSnapshot systemSnapshot) {
        final boolean active = enabled && worldToken != null;
        if (active) {
            Objects.requireNonNull(systemSnapshot, "systemSnapshot");
            if (refreshIntervalMillis <= 0) {
                throw new IllegalArgumentException("refreshIntervalMillis must be positive: " + refreshIntervalMillis);
            }
        }
        updateActivation(worldToken, active);
        if (!active) {
            return IndicatorSnapshot.empty();
        }
        if (this.published && endNanos - this.lastPublishNanos < refreshIntervalMillis * NANOS_PER_MILLI) {
            return this.latest;
        }
        publish(endNanos, systemSnapshot);
        return this.latest;
    }

    private void publish(long endNanos, SystemMetricsSnapshot systemSnapshot) {
        final FrameStatistics statistics = this.timeline.snapshot(endNanos);
        this.fps = this.fps.publish(statistics.currentFps());
        this.fpsLow = this.fpsLow.publish(statistics.onePercentLowFps());
        this.memory = this.memory.publish(systemSnapshot.memoryUsedBytes());
        this.latest = new IndicatorSnapshot(this.fps, this.fpsLow, this.memory, systemSnapshot);
        this.lastPublishNanos = endNanos;
        this.published = true;
    }

    private void updateActivation(Object worldToken, boolean active) {
        if (active == this.active && (!active || isTrackedWorld(worldToken))) {
            return;
        }
        this.timeline.reset();
        this.fps = MetricStatistic.empty();
        this.fpsLow = MetricStatistic.empty();
        this.memory = MetricStatistic.empty();
        this.latest = IndicatorSnapshot.empty();
        this.published = false;
        this.active = active;
        this.worldToken = active ? worldToken : null;
    }

    private boolean isTrackedWorld(Object worldToken) {
        return this.worldToken == worldToken || (this.worldToken != null && this.worldToken.equals(worldToken));
    }
}
