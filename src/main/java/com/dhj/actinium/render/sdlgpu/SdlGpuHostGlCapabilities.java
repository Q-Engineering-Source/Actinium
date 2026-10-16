package com.dhj.actinium.render.sdlgpu;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/**
 * Holds the host window's real GL capabilities on the SDL GPU path.
 *
 * <p>The backend claims the window at display-creation time and no usable GL context remains
 * afterwards, but vanilla {@code OpenGlHelper.initializeTextures()} still calls
 * {@link GLContext#getCapabilities()}, which under LWJGLY probes the driver natively and crashes
 * the JVM. Capturing the capabilities while the host context is still current (after the bridge
 * adopted it, before the backend claims the window) lets that probe be answered from real data.</p>
 */
public final class SdlGpuHostGlCapabilities {

    private static ContextCapabilities captured;

    private SdlGpuHostGlCapabilities() {
    }

    /**
     * Snapshots the current (host) GL capabilities. Must run while the host GL context is current.
     */
    public static void capture() {
        captured = GLContext.getCapabilities();
    }

    /**
     * @return the capabilities captured at display-creation time, or null if never captured
     */
    public static ContextCapabilities captured() {
        return captured;
    }
}
