package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.lwjgl.opengl.GL15;

/**
 * Managed-code boundaries for terrain rendering.
 *
 * <p>{@link RenderDevice} unbinds the array buffer through the raw GL entry point, which GLSM's cached
 * {@code boundVBO} never sees. Vanilla code that runs after the boundary reads that cache to decide whether a client
 * array pointer describes buffer offset or client memory, so the cache is cleared here as well. The reset runs on both
 * edges because the device drops its own binding tracker on each edge and callers on either side rely on buffer 0.
 */
public final class ManagedCodeBoundary {

    private ManagedCodeBoundary() {
    }

    public static void enter() {
        RenderDevice.enterManagedCode();
        resetArrayBufferBinding();
    }

    public static void exit() {
        RenderDevice.exitManagedCode();
        resetArrayBufferBinding();
    }

    private static void resetArrayBufferBinding() {
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }
}
