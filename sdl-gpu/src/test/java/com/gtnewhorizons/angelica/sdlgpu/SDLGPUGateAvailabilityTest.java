package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of the SDL GPU availability gate that can be decided without a GPU device.
 *
 * <p>The gate is a short-circuit conjunction of four conditions: SDL GPU was requested, its runtime
 * dependencies are on the classpath, the bridge that supplies the window is installed, and the GPU
 * device could be created. The last two cannot be exercised here - the request flag is a process-wide
 * system property and the device probe talks to real SDL - so this test pins the parts that must
 * never regress: an OpenGL run stays off, the dependency probe passes when lwjgl-sdl is present, and
 * the gate consults the request flag before anything that could touch a device.</p>
 */
@Timeout(10)
class SDLGPUGateAvailabilityTest {

    @Test
    void staysOffWhenSDLGPUIsNotRequested() {
        assertFalse(
            BackendOptions.sdlGpuRequested(),
            "these tests must run without -Dangelica.sdlgpu.enable; the device probe would run for real");
        assertFalse(SDLGPUGate.isHandoverAvailable());
        assertFalse(SDLGPUGate.isDeviceReady());
    }

    @Test
    void findsItsRuntimeDependencies() {
        assertTrue(SDLGPUGate.isSDLGPUAvailable(), SDLGPUGate::missingRequirement);
    }
}
