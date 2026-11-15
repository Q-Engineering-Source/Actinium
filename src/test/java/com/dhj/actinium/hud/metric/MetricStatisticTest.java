package com.dhj.actinium.hud.metric;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricStatisticTest {
    @Test
    void theFirstValueSeedsTheWholeRange() {
        MetricStatistic statistic = MetricStatistic.empty().publish(OptionalDouble.of(60.0));

        assertEquals(60.0, statistic.current().getAsDouble());
        assertEquals(60.0, statistic.min().getAsDouble());
        assertEquals(60.0, statistic.max().getAsDouble());
    }

    @Test
    void aHigherValueOnlyRaisesTheMaximum() {
        MetricStatistic statistic = MetricStatistic.empty()
            .publish(OptionalDouble.of(60.0))
            .publish(OptionalDouble.of(75.0));

        assertEquals(75.0, statistic.current().getAsDouble());
        assertEquals(60.0, statistic.min().getAsDouble());
        assertEquals(75.0, statistic.max().getAsDouble());
    }

    @Test
    void aLowerValueOnlyLowersTheMinimum() {
        MetricStatistic statistic = MetricStatistic.empty()
            .publish(OptionalDouble.of(60.0))
            .publish(OptionalDouble.of(45.0));

        assertEquals(45.0, statistic.current().getAsDouble());
        assertEquals(45.0, statistic.min().getAsDouble());
        assertEquals(60.0, statistic.max().getAsDouble());
    }

    @Test
    void anEmptyPublishClearsTheCurrentAndKeepsTheRange() {
        MetricStatistic statistic = MetricStatistic.empty()
            .publish(OptionalDouble.of(40.0))
            .publish(OptionalDouble.of(90.0))
            .publish(OptionalDouble.empty());

        assertTrue(statistic.current().isEmpty());
        assertEquals(40.0, statistic.min().getAsDouble());
        assertEquals(90.0, statistic.max().getAsDouble());
    }

    @Test
    void emptyPublishesBeforeTheFirstValueLeaveTheRangeEmpty() {
        MetricStatistic statistic = MetricStatistic.empty()
            .publish(OptionalDouble.empty())
            .publish(OptionalDouble.empty());

        assertTrue(statistic.current().isEmpty());
        assertTrue(statistic.min().isEmpty());
        assertTrue(statistic.max().isEmpty());

        MetricStatistic seeded = statistic.publish(OptionalDouble.of(12.0));

        assertEquals(12.0, seeded.min().getAsDouble());
        assertEquals(12.0, seeded.max().getAsDouble());
    }

    @Test
    void publishingReturnsANewInstanceAndLeavesThePreviousOneUntouched() {
        MetricStatistic first = MetricStatistic.empty().publish(OptionalDouble.of(60.0));

        MetricStatistic second = first.publish(OptionalDouble.of(30.0));

        assertNotSame(first, second);
        assertEquals(60.0, first.current().getAsDouble());
        assertEquals(60.0, first.min().getAsDouble());
        assertEquals(30.0, second.min().getAsDouble());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class,
            () -> new MetricStatistic(null, OptionalDouble.empty(), OptionalDouble.empty()));
        assertThrows(NullPointerException.class,
            () -> new MetricStatistic(OptionalDouble.empty(), null, OptionalDouble.empty()));
        assertThrows(NullPointerException.class,
            () -> new MetricStatistic(OptionalDouble.empty(), OptionalDouble.empty(), null));
        assertThrows(NullPointerException.class, () -> MetricStatistic.empty().publish(null));
    }
}
