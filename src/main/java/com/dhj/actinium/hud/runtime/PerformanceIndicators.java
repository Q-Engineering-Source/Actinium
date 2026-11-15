package com.dhj.actinium.hud.runtime;

import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Holds the performance indicator of this client.
 *
 * <p>The instance is created on the first render tick, which keeps the configuration read and the
 * hardware source selection out of the mod construction phase. Both entry points run on the client
 * thread: {@link #onRenderTick(TickEvent.RenderTickEvent)} for the whole session and {@link #shutdown()}
 * once the client stops.
 */
public final class PerformanceIndicators {
    private static PerformanceIndicator indicator;

    private PerformanceIndicators() {
    }

    /**
     * Forwards a render tick to the indicator, creating it on first use.
     *
     * @param event phase and partial tick of the render tick
     */
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        PerformanceIndicator current = indicator;
        if (current == null) {
            current = new PerformanceIndicatorImpl();
            indicator = current;
        }
        current.onRenderTick(event);
    }

    /**
     * Releases the indicator and forgets it, so a repeated call does nothing.
     *
     * <p>The instance leaves the holder before it is released, which keeps the sampling source of a
     * failed release from being closed twice.
     */
    public static void shutdown() {
        final PerformanceIndicator current = indicator;
        if (current == null) {
            return;
        }
        indicator = null;
        current.shutdown();
    }
}
