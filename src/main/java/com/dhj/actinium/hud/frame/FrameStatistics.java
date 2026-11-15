package com.dhj.actinium.hud.frame;

import java.util.OptionalDouble;
import java.util.Objects;

/**
 * Frame rate values derived from the samples a {@link FrameTimeline} retains.
 *
 * <p>A value is empty when its window does not hold enough samples to compute it.
 *
 * @param currentFps mean frame rate of the samples inside the last second
 * @param onePercentLowFps frame rate of the worst one percent of the samples inside the last ten seconds
 */
public record FrameStatistics(OptionalDouble currentFps, OptionalDouble onePercentLowFps) {
    public FrameStatistics {
        Objects.requireNonNull(currentFps, "currentFps");
        Objects.requireNonNull(onePercentLowFps, "onePercentLowFps");
    }

    /** {@return statistics without a single sample} */
    public static FrameStatistics empty() {
        return new FrameStatistics(OptionalDouble.empty(), OptionalDouble.empty());
    }
}
