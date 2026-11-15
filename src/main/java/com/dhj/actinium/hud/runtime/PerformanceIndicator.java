package com.dhj.actinium.hud.runtime;

import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Runs the performance indicator from the render tick.
 *
 * <p>Both phases act on the render thread. The start phase records the frame clock and keeps the
 * hardware sampling in step with the configuration; the end phase publishes the values of the finished
 * frame and draws them while the indicator is visible.
 */
public interface PerformanceIndicator {
    /**
     * Feeds one render tick phase.
     *
     * @param event phase and partial tick of the render tick
     */
    void onRenderTick(TickEvent.RenderTickEvent event);

    /**
     * Releases the hardware sampling this indicator owns.
     *
     * <p>Repeated calls through {@link PerformanceIndicators} reach at most one instance, and closing
     * the sampling source twice has no effect.
     */
    void shutdown();
}
