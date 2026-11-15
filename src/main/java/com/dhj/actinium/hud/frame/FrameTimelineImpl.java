package com.dhj.actinium.hud.frame;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * Ring buffer implementation of {@link FrameTimeline}.
 *
 * <p>The samples are held oldest to newest in two parallel arrays of frame starts and frame
 * intervals. The arrays start at {@link #INITIAL_CAPACITY} slots and double whenever a frame does
 * not fit, which copies the retained samples back into oldest to newest order. Recording a frame
 * only writes into the arrays; a snapshot walks the newest samples backwards until it leaves the
 * window it needs, so the one second average never sorts or copies a sample.
 *
 * <p>A frame that does not advance the clock becomes the new origin but yields no sample, which
 * keeps zero and negative intervals out of the averages.
 */
public final class FrameTimelineImpl implements FrameTimeline {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long FRAME_RATE_WINDOW_NANOS = NANOS_PER_SECOND;
    private static final long LOW_FRAME_RATE_WINDOW_NANOS = 10 * NANOS_PER_SECOND;
    private static final int LOW_FRAME_RATE_MIN_SAMPLES = 100;
    private static final int LOW_FRAME_RATE_PERCENT_DIVISOR = 100;
    private static final int INITIAL_CAPACITY = 512;

    private long[] sampleTimes = new long[INITIAL_CAPACITY];
    private long[] sampleIntervals = new long[INITIAL_CAPACITY];
    private int oldest;
    private int size;

    private boolean hasLastFrameStart;
    private long lastFrameStartNanos;

    @Override
    public void onFrame(long frameStartNanos) {
        if (!this.hasLastFrameStart) {
            this.hasLastFrameStart = true;
            this.lastFrameStartNanos = frameStartNanos;
            return;
        }

        final long intervalNanos = frameStartNanos - this.lastFrameStartNanos;
        this.lastFrameStartNanos = frameStartNanos;
        evictSamplesBefore(frameStartNanos - LOW_FRAME_RATE_WINDOW_NANOS);
        if (intervalNanos > 0L) {
            append(frameStartNanos, intervalNanos);
        }
    }

    @Override
    public FrameStatistics snapshot(long nowNanos) {
        return new FrameStatistics(currentFps(nowNanos), lowFps(nowNanos));
    }

    @Override
    public void reset() {
        this.hasLastFrameStart = false;
        this.lastFrameStartNanos = 0L;
        this.oldest = 0;
        this.size = 0;
    }

    /** {@return the mean frame rate of the samples that ended inside the last second} */
    private OptionalDouble currentFps(long nowNanos) {
        final long cutoffNanos = nowNanos - FRAME_RATE_WINDOW_NANOS;
        long sumNanos = 0L;
        int sampleCount = 0;
        for (int offset = this.size - 1; offset >= 0; offset--) {
            final int index = slot(offset);
            if (this.sampleTimes[index] < cutoffNanos) {
                break;
            }
            sumNanos += this.sampleIntervals[index];
            sampleCount++;
        }
        if (sampleCount == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(NANOS_PER_SECOND / ((double) sumNanos / sampleCount));
    }

    /**
     * {@return the frame rate of the worst one percent of the samples that ended inside the last ten
     * seconds, or an empty optional while the window holds fewer than
     * {@link #LOW_FRAME_RATE_MIN_SAMPLES} samples}
     */
    private OptionalDouble lowFps(long nowNanos) {
        final long cutoffNanos = nowNanos - LOW_FRAME_RATE_WINDOW_NANOS;
        int sampleCount = 0;
        while (sampleCount < this.size && this.sampleTimes[slot(this.size - 1 - sampleCount)] >= cutoffNanos) {
            sampleCount++;
        }
        if (sampleCount < LOW_FRAME_RATE_MIN_SAMPLES) {
            return OptionalDouble.empty();
        }

        final int worstCount = Math.ceilDiv(sampleCount, LOW_FRAME_RATE_PERCENT_DIVISOR);
        final long[] sortedIntervals = new long[sampleCount];
        for (int offset = 0; offset < sampleCount; offset++) {
            sortedIntervals[offset] = this.sampleIntervals[slot(this.size - sampleCount + offset)];
        }
        Arrays.sort(sortedIntervals);

        long sumNanos = 0L;
        for (int index = sampleCount - worstCount; index < sampleCount; index++) {
            sumNanos += sortedIntervals[index];
        }
        return OptionalDouble.of(NANOS_PER_SECOND / ((double) sumNanos / worstCount));
    }

    private void evictSamplesBefore(long cutoffNanos) {
        int evicted = 0;
        while (evicted < this.size && this.sampleTimes[this.oldest] < cutoffNanos) {
            this.oldest = (this.oldest + 1) % this.sampleTimes.length;
            evicted++;
        }
        this.size -= evicted;
    }

    private void append(long frameStartNanos, long intervalNanos) {
        if (this.size == this.sampleTimes.length) {
            grow();
        }
        final int index = slot(this.size);
        this.sampleTimes[index] = frameStartNanos;
        this.sampleIntervals[index] = intervalNanos;
        this.size++;
    }

    private void grow() {
        final int capacity = this.sampleTimes.length * 2;
        final long[] times = new long[capacity];
        final long[] intervals = new long[capacity];
        for (int offset = 0; offset < this.size; offset++) {
            times[offset] = this.sampleTimes[slot(offset)];
            intervals[offset] = this.sampleIntervals[slot(offset)];
        }
        this.sampleTimes = times;
        this.sampleIntervals = intervals;
        this.oldest = 0;
    }

    private int slot(int offsetFromOldest) {
        return (this.oldest + offsetFromOldest) % this.sampleTimes.length;
    }
}
