package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCulling;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCullingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted option decides whether the compute culler is allowed to take over the draw, so applying the option
 * has to move the culler's active mode in both directions.
 */
class GpuCullingSettingsTest {
    @AfterEach
    void restoreCpuOnlyMode() {
        GpuCulling.setMode(GpuCullingMode.CPU_ONLY);
    }

    @Test
    void computeSelectionEnablesTheComputePath() {
        SodiumGameOptions options = new SodiumGameOptions();
        options.performance.gpuCullingMode = GpuCullingMode.COMPUTE;

        GpuCullingSettings.apply(options);

        assertTrue(GpuCulling.mode().computeEnabled());
    }

    @Test
    void cpuOnlySelectionKeepsTheCpuPath() {
        SodiumGameOptions options = new SodiumGameOptions();
        options.performance.gpuCullingMode = GpuCullingMode.CPU_ONLY;

        GpuCullingSettings.apply(options);

        assertFalse(GpuCulling.mode().computeEnabled());
    }
}
