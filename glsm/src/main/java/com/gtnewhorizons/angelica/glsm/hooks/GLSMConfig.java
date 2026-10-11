package com.gtnewhorizons.angelica.glsm.hooks;

import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.GLStateManager;

public final class GLSMConfig {

    /** Lightmap coords (replaces OpenGlHelper.lastBrightnessX/Y) live per drawable context. */
    public static int packBrightness(float x, float y) {
        return ((int) y << 16) | ((int) x & 0xFFFF);
    }

    public static int packedLastBrightness() {
        final GLContextState glCtx = GLStateManager.ctx();
        return packBrightness(glCtx.lastBrightnessX, glCtx.lastBrightnessY);
    }

    // True while StellarCore renders HUD content into its cached framebuffer
    // (HUDCaching.renderingCacheOverride). Mirrors the alpha-factor override
    // StellarCore applies through its vanilla GlStateManager mixins, which are
    // bypassed by the GLSM redirector.
    public static boolean hudCacheOverride;

    // Blend enabled/disabled as seen inside the HUD cache rendering window;
    // mirrors StellarCore's HUDCaching blendEnabled bookkeeping used by its
    // color interceptor (translucent colors are forced opaque when blending is
    // disabled so the cache buffer alpha stays intact).
    public static boolean hudCacheBlendEnabled;

    // SDL GPU backend: expands vertex formats to the width the backend's attribute
    // pipeline expects; set during backend init.
    public static volatile boolean expandVertexFormats;
    /** Configured worker thread count; 0 selects the automatic 2..12 default. */
    public static int workerThreadCount;

    private GLSMConfig() {}
}
