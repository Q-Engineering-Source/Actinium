package com.dhj.actinium.hud.frame;

/**
 * Records frame starts and derives the frame rate and the one percent low frame rate from them.
 *
 * <p>The clock belongs to the caller, so the timeline holds nothing but the samples it was fed and
 * can be driven by synthetic time. The render thread feeds every frame with {@link #onFrame(long)};
 * snapshots and {@link #reset()} run on that same thread.
 */
public interface FrameTimeline {
    /**
     * Records the start of a frame.
     *
     * <p>The first call only establishes the origin. Every later call stores the frame start and the
     * interval to the previously recorded one, then drops the samples that left the ten second
     * retention window.
     *
     * @param frameStartNanos frame start on the clock the caller drives the timeline with
     */
    void onFrame(long frameStartNanos);

    /**
     * Reports the statistics of the samples its windows hold at {@code nowNanos}.
     *
     * @param nowNanos time the windows end at, on the same clock the frames were recorded with
     * @return statistics of the retained samples
     */
    FrameStatistics snapshot(long nowNanos);

    /** Forgets the last frame start and every retained sample, so the next frame starts a new timeline. */
    void reset();
}
