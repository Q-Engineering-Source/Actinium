package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCulling;

/**
 * Host entry point that makes the culling core follow the persisted GPU culling option. Startup and the advanced
 * options page both go through here, so a mode restored from the config file and a mode picked in the GUI leave the
 * culler in the same state.
 */
public final class GpuCullingSettings {
    private GpuCullingSettings() {
    }

    /** Applies {@link SodiumGameOptions.PerformanceSettings#gpuCullingMode} to the active culler. */
    public static void apply(SodiumGameOptions options) {
        GpuCulling.setMode(options.performance.gpuCullingMode);
    }
}
