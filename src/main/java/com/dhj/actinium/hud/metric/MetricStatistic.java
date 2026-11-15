package com.dhj.actinium.hud.metric;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Current value and observed range of one metric the performance indicator shows.
 *
 * <p>A value is empty when its source did not report it, so the range keeps the values published
 * before instead of treating the absence as a measurement. Instances are immutable:
 * {@link #publish(OptionalDouble)} returns the statistic to show after the next publish.
 *
 * @param current value of the most recent publish, empty when that publish carried none
 * @param min lowest value published since the range started, empty while the range holds no value
 * @param max highest value published since the range started, empty while the range holds no value
 */
public record MetricStatistic(OptionalDouble current, OptionalDouble min, OptionalDouble max) {
    public MetricStatistic {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
    }

    /** {@return a statistic without a current value and without a range} */
    public static MetricStatistic empty() {
        return new MetricStatistic(OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());
    }

    /**
     * Publishes the next value.
     *
     * <p>A present value becomes the current value and widens the range when it falls outside it. An
     * empty value clears the current value and keeps the range, so a publish without a measurement
     * stays out of the extremes.
     *
     * @param next value of the next publish
     * @return statistic holding {@code next} and the range that includes it
     */
    public MetricStatistic publish(OptionalDouble next) {
        Objects.requireNonNull(next, "next");
        if (next.isEmpty()) {
            return new MetricStatistic(OptionalDouble.empty(), this.min, this.max);
        }
        final double value = next.getAsDouble();
        return new MetricStatistic(
            next,
            this.min.isEmpty() ? next : OptionalDouble.of(Math.min(this.min.getAsDouble(), value)),
            this.max.isEmpty() ? next : OptionalDouble.of(Math.max(this.max.getAsDouble(), value)));
    }
}
