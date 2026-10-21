package com.dhj.actinium.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the prefetch window, the in-order consumption contract and the failure paths of the lane.
 */
class PrefetchLaneTest {

    /** Records what the lane submitted and what it disposed of, running tasks inline so the test is deterministic. */
    private static final class Recorder<T> {

        final List<T> disposed = new ArrayList<>();
        int submitted;

        PrefetchLane<Integer, T> lane(int window) {
            return new PrefetchLane<>(
                task -> {
                    this.submitted++;
                    return CompletableFuture.completedFuture(task.get());
                },
                window,
                this.disposed::add);
        }
    }

    @Test
    void onlyTheWindowIsSubmittedUpFront() {
        final Recorder<String> recorder = new Recorder<>();
        final PrefetchLane<Integer, String> lane = recorder.lane(2);
        for (int i = 0; i < 6; i++) {
            final int key = i;
            lane.add(key, () -> "value" + key);
        }

        lane.start();
        assertEquals(2, recorder.submitted, "the lane must not run ahead of its window");

        assertEquals("value0", lane.take(0));
        assertEquals(3, recorder.submitted, "consuming a slot frees one window slot");
    }

    @Test
    void skippedSlotsAreDisposedInsteadOfDelivered() {
        final Recorder<String> recorder = new Recorder<>();
        final PrefetchLane<Integer, String> lane = recorder.lane(4);
        for (int i = 0; i < 4; i++) {
            final int key = i;
            lane.add(key, () -> "value" + key);
        }

        lane.start();
        assertEquals("value2", lane.take(2), "a later slot is still reachable");
        assertEquals(List.of("value0", "value1"), recorder.disposed, "the skipped values are handed to the disposer");
        assertEquals(0, lane.failures());
    }

    @Test
    void aMissedKeyDesynchronisesAndClosesTheLane() {
        final Recorder<String> recorder = new Recorder<>();
        final PrefetchLane<Integer, String> lane = recorder.lane(2);
        lane.add(0, () -> "value0");
        lane.add(1, () -> "value1");
        lane.start();

        assertEquals("value1", lane.take(1));
        assertFalse(lane.isDesynced(), "taking a later slot in order is not a desync");
        assertNull(lane.take(0), "a consumed or missed key yields null");
        assertTrue(lane.isDesynced(), "asking for a key the cursor passed must mark the lane desynchronised");
        assertNull(lane.take(1), "a closed lane yields null even for unknown keys");
    }

    @Test
    void failuresAreRecordedAndReportedAsNoValue() {
        final Recorder<String> recorder = new Recorder<>();
        final PrefetchLane<Integer, String> lane = recorder.lane(2);
        lane.add(0, () -> {
            throw new IllegalStateException("decode failed");
        });
        lane.start();

        assertNull(lane.take(0));
        assertEquals(1, lane.failures());
        assertTrue(lane.firstFailure() instanceof IllegalStateException);
        assertSame(lane.firstFailure(), lane.firstFailure());
    }

    @Test
    void closingRecordsAnUnconsumedFailureExactlyOnce() {
        final IllegalStateException failure = new IllegalStateException("decode failed before its sprite was requested");
        final PrefetchLane<Integer, String> lane = new PrefetchLane<>(
            task -> CompletableFuture.failedFuture(failure), 1, value -> {});
        lane.add(0, () -> "unused");

        lane.start();
        lane.close();
        lane.awaitQuiescence();

        assertEquals(1, lane.failures(), "closing joins failures even when no consumer took the slot");
        assertEquals(0, lane.cancellations());
        assertSame(failure, lane.firstFailure());
    }

    @Test
    void aConsumedFailureIsNotCountedAgainWhenClosing() {
        final IllegalStateException failure = new IllegalStateException("decode failed");
        final PrefetchLane<Integer, String> lane = new PrefetchLane<>(
            task -> CompletableFuture.failedFuture(failure), 1, value -> {});
        lane.add(0, () -> "unused");

        lane.start();
        assertNull(lane.take(0));
        lane.close();
        lane.awaitQuiescence();

        assertEquals(1, lane.failures(), "joining and awaiting one failed slot still records a single failure");
        assertEquals(0, lane.cancellations());
        assertSame(failure, lane.firstFailure());
    }

    @Test
    void cancelledWorkIsCountedSeparatelyFromDecodeFailures() {
        final CompletableFuture<String> pending = new CompletableFuture<>();
        final PrefetchLane<Integer, String> lane = new PrefetchLane<>(task -> pending, 1, value -> {});
        lane.add(0, () -> "cancelled");

        lane.start();
        pending.cancel(false);
        lane.close();
        lane.awaitQuiescence();

        assertEquals(0, lane.failures(), "normal shutdown cancellation is not a decode failure");
        assertEquals(1, lane.cancellations(), "a cancelled submitted future is still counted once");
        assertNull(lane.firstFailure());
    }

    @Test
    void closingDisposesWhatItHasNotConsumed() {
        final Recorder<String> recorder = new Recorder<>();
        final PrefetchLane<Integer, String> lane = recorder.lane(4);
        for (int i = 0; i < 3; i++) {
            final int key = i;
            lane.add(key, () -> "value" + key);
        }
        lane.start();
        assertEquals("value0", lane.take(0));

        lane.close();
        lane.awaitQuiescence();

        assertEquals(List.of("value1", "value2"), recorder.disposed);
        assertNull(lane.take(1), "a closed lane stops handing out values");
    }

    @Test
    void aNullTaskIsSkippedWithoutSubmitting() {
        final Recorder<String> recorder = new Recorder<>();
        final PrefetchLane<Integer, String> lane = recorder.lane(4);
        lane.add(0, null);
        lane.add(1, () -> "value1");
        lane.start();

        assertEquals(1, recorder.submitted);
        assertEquals("value1", lane.take(1));
        assertNull(lane.take(0), "a slot without a task still occupies its position in the order");
    }

    @Test
    void aWindowBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PrefetchLane<>(CompletableFuture::supplyAsync, 0, value -> {}));
    }

    /** A submission that throws must be counted as a failure rather than aborting the lane. */
    @Test
    void aFailingSubmitterIsCounted() {
        final AtomicInteger attempts = new AtomicInteger();
        final PrefetchLane<Integer, String> lane = new PrefetchLane<>(
            task -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("no threads left");
                }
                return CompletableFuture.supplyAsync(task);
            },
            2,
            value -> {});
        lane.add(0, () -> "value0");
        lane.add(1, () -> "value1");
        lane.start();

        assertEquals(1, lane.failures());
        assertNull(lane.take(0));
        assertEquals("value1", lane.take(1));
    }
}
