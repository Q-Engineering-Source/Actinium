package com.dhj.actinium.render.pacing;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Deadline sleep for the frame pacer.
 *
 * <p>A frame must start on the pacing deadline and not before it, but an operating system timer
 * never wakes a thread exactly when asked: it wakes late by a platform-dependent amount that also
 * jitters. Parking the whole remaining interval therefore overshoots the deadline, while spinning
 * the whole interval burns a core. This sleeper measures how late each park actually returned,
 * keeps a running estimate of that lag over a sliding window, and parks only down to the deadline
 * minus the estimate minus a spin floor wide enough to cover the observed jitter. The remainder is
 * spun, so the frame lands on the deadline instead of late.
 *
 * <p>All state is per-instance and single-threaded: the pacer drives it from the client thread.
 */
final class PacerSleeper {

    private static final long MIN_PARK_NANOS = 1_000_000L;
    private static final long MAX_OVERSHOOT_NANOS = 4_000_000L;
    static final int SLOTS = 10;
    static final long LATE_TOL_NANOS = 250_000L;

    static final long MIN_SPIN_FLOOR_NANOS = 20_000L;
    private static final long MAX_SPIN_FLOOR_NANOS = 500_000L;

    private final LongSupplier clock;
    private final LongConsumer parker;
    private final boolean spinWait;
    private final long[] overshoots = new long[SLOTS];
    private long overshootSum;
    private long spinFloorNanos = MIN_SPIN_FLOOR_NANOS;
    private int offset;

    long lastFrameSpinNanos;
    private int frameParks;
    private long frameSpinNanos;

    private long frames;
    private long slackSum;
    private long parkTotal;
    private long lateFrames;
    private long lateMax;

    PacerSleeper(LongSupplier clock, LongConsumer parker, boolean spinWait) {
        this.clock = clock;
        this.parker = parker;
        this.spinWait = spinWait;
    }

    /**
     * Blocks until {@code deadlineNanos} and returns the clock reading at which the caller may
     * start the frame. {@code now} is the caller's current clock reading; an already-late caller
     * returns immediately.
     */
    long sleepUntil(long deadlineNanos, long now) {
        long spinFloor = spinFloorNanos;
        while (true) {
            final long remaining = deadlineNanos - now;
            if (remaining <= spinFloor) break;
            if (Thread.currentThread().isInterrupted()) return clock.getAsLong();

            long request = remaining - avgOvershootNanos() - spinFloor;
            if (request <= 0L) {
                if (remaining <= MIN_PARK_NANOS) break;
                request = MIN_PARK_NANOS;
            }

            final long before = now;
            parker.accept(request);
            now = clock.getAsLong();
            record(Math.min(Math.max(0L, now - before - request), MAX_OVERSHOOT_NANOS));
            spinFloor = spinFloorNanos;
            frameParks++;
        }

        if (spinWait) {
            final long spinStart = now;
            while (now < deadlineNanos) {
                if (deadlineNanos - now > spinFloor) Thread.yield();
                else Thread.onSpinWait();
                now = clock.getAsLong();
            }
            frameSpinNanos += now - spinStart;
        }
        return now;
    }

    /** Closes the bookkeeping for one paced frame; called once per frame by the pacer. */
    void noteFrame(long slackNanos, long wakeLateNanos) {
        frames++;
        slackSum += slackNanos;
        parkTotal += frameParks;
        frameParks = 0;
        lastFrameSpinNanos = frameSpinNanos;
        frameSpinNanos = 0L;
        if (wakeLateNanos > LATE_TOL_NANOS) {
            lateFrames++;
            if (wakeLateNanos > lateMax) lateMax = wakeLateNanos;
        }
    }

    void resetStats() {
        frameParks = 0;
        frameSpinNanos = 0L;
        lastFrameSpinNanos = 0L;
        frames = 0L;
        slackSum = 0L;
        parkTotal = 0L;
        lateFrames = 0L;
        lateMax = 0L;
    }

    String summary(String config) {
        if (frames() == 0L) return "pacer: " + config + " no paced frames";
        return "pacer: " + config + " frames=" + frames() + " parks=" + parkTotal() + " slackUs avg=" + slackSum / frames() / 1000L
            + " floorUs=" + spinFloorNanos() / 1000L + " overshootUs est=" + avgOvershootNanos() / 1000L
            + " lateFrames=" + lateFrames() + " lateMaxUs=" + lateMax() / 1000L;
    }

    long avgOvershootNanos() {
        return overshootSum / SLOTS;
    }

    long spinFloorNanos() {
        return spinFloorNanos;
    }

    long frames() {
        return frames;
    }

    long parkTotal() {
        return parkTotal;
    }

    long lateFrames() {
        return lateFrames;
    }

    long lateMax() {
        return lateMax;
    }

    /**
     * Folds one measured park overshoot into the sliding window and re-derives the spin floor from
     * the spread between the worst overshoot and the mean: only jitter above the mean has to be
     * spun off, so a steady platform keeps the minimum floor while a jittery one widens it.
     */
    private void record(long nanos) {
        overshootSum += nanos - overshoots[offset];
        overshoots[offset] = nanos;
        offset = (offset + 1) % SLOTS;

        long max = 0L;
        for (final long o : overshoots) {
            if (o > max) max = o;
        }
        final long jitter = max - avgOvershootNanos();
        spinFloorNanos = Math.max(MIN_SPIN_FLOOR_NANOS, Math.min(MAX_SPIN_FLOOR_NANOS, jitter));
    }
}
