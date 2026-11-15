package com.dhj.actinium.hud.metric;

import com.dhj.actinium.hud.system.SystemMetricsSnapshot;

/**
 * Publishes the values the performance indicator shows.
 *
 * <p>Both methods run on the render thread, so an implementation holds its state without
 * synchronization. Every call receives the two inputs that decide whether the statistics are live:
 * the master switch of the indicator and the token of the loaded world. The statistics are active
 * while the switch is on and a world token is present; key toggles and open screens are not inputs
 * and leave the statistics as they are.
 *
 * <p>The frame samples behind the frame rate belong to one world and start over whenever the world
 * token changes or the statistics leave or enter the active state.
 */
public interface IndicatorMetrics {
    /**
     * Records the frame that starts at {@code startNanos}.
     *
     * <p>An active call feeds the frame clock into the timeline. A changed world token, a transition
     * from inactive to active and a transition to inactive each drop the retained frame samples, the
     * last snapshot, the publish gate and every extreme.
     *
     * @param startNanos frame start on the clock {@code onFrameEnd} continues with
     * @param worldToken token of the loaded world, or {@code null} while no world is loaded
     * @param enabled whether the indicator is switched on
     */
    void onFrameStart(long startNanos, Object worldToken, boolean enabled);

    /**
     * Publishes the values for the frame that ends at {@code endNanos}.
     *
     * <p>An inactive call returns {@link IndicatorSnapshot#empty()} and keeps nothing. An active call
     * publishes at most once per {@code refreshIntervalMillis}: a call inside the running interval
     * returns the snapshot of the last publish and leaves the extremes as they are. A publish reads
     * the timeline at {@code endNanos}, records the frame rate, the one percent low frame rate and the
     * used system memory into their ranges, and stores {@code systemSnapshot} as the hardware values.
     * The first active call after the statistics started over publishes immediately.
     *
     * @param endNanos frame end on the clock the frame starts were recorded with
     * @param worldToken token of the loaded world, or {@code null} while no world is loaded
     * @param enabled whether the indicator is switched on
     * @param refreshIntervalMillis delay between two publishes, in milliseconds
     * @param systemSnapshot hardware values sampled for this frame
     * @return the published snapshot, or the snapshot of the last publish while its interval still runs
     * @throws IllegalArgumentException when the call is active and {@code refreshIntervalMillis} is not
     *                                  positive
     * @throws NullPointerException when the call is active and {@code systemSnapshot} is {@code null}
     */
    IndicatorSnapshot onFrameEnd(long endNanos, Object worldToken, boolean enabled, int refreshIntervalMillis,
                                 SystemMetricsSnapshot systemSnapshot);
}
