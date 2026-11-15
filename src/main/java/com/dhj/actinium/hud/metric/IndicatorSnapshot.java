package com.dhj.actinium.hud.metric;

import com.dhj.actinium.hud.system.SystemMetricsSnapshot;

import java.util.Objects;

/**
 * Everything the performance indicator shows for one publish.
 *
 * <p>{@code fps}, {@code fpsLow} and {@code memory} carry the published history of the frame rate,
 * the one percent low frame rate and the used system memory. {@code system} carries the hardware
 * values sampled for the same frame, including the CPU, GPU, VRAM and RAM values the indicator
 * renders as they are.
 *
 * @param fps frame rate of the most recent publish and its observed range
 * @param fpsLow one percent low frame rate of the most recent publish and its observed range
 * @param memory used system memory of the most recent publish and its observed range
 * @param system hardware values sampled for the same frame
 */
public record IndicatorSnapshot(MetricStatistic fps, MetricStatistic fpsLow, MetricStatistic memory,
                                SystemMetricsSnapshot system) {
    public IndicatorSnapshot {
        Objects.requireNonNull(fps, "fps");
        Objects.requireNonNull(fpsLow, "fpsLow");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(system, "system");
    }

    /** {@return a snapshot without a published value and without hardware values} */
    public static IndicatorSnapshot empty() {
        return new IndicatorSnapshot(
            MetricStatistic.empty(),
            MetricStatistic.empty(),
            MetricStatistic.empty(),
            SystemMetricsSnapshot.empty());
    }
}
