package com.dhj.actinium.compat.extrautils2;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Live GL state queries for the Extra Utilities 2 GUI compatibility (issue #48).
 *
 * <p>Extra Utilities 2's {@code GLStateAttributes} snapshots vanilla {@code GlStateManager}
 * fields so its {@code restore()} can bring the GL state back after each widget renders. Under
 * Actinium those fields are never updated (GLSM redirects the method calls), so the snapshot is
 * stale. Re-reading through GLSM returns the cached logical state where a cache exists and falls
 * back to live queries otherwise, which is correct on the core-profile context (plain queries
 * for e.g. {@code GL_TEXTURE_2D} are invalid there).</p>
 */
public final class ExtraUtils2GLStateCompat {
    private ExtraUtils2GLStateCompat() {
    }

    public static int getInteger(int pname) {
        return GLStateManager.glGetInteger(pname);
    }

    public static void getInteger(int pname, IntBuffer params) {
        GLStateManager.glGetInteger(pname, params);
    }

    public static float getFloat(int pname) {
        return GLStateManager.glGetFloat(pname);
    }

    public static void getFloat(int pname, FloatBuffer params) {
        GLStateManager.glGetFloat(pname, params);
    }

    public static boolean isEnabled(int cap) {
        return GLStateManager.glIsEnabled(cap);
    }
}