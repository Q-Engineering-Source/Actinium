package com.gtnewhorizons.angelica.glsm.hooks;

import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.GLStateManager;

public final class GLSMConfig {

    public static float getLastBrightnessX() {
        return GLStateManager.getLastBrightnessX();
    }

    public static float getLastBrightnessY() {
        return GLStateManager.getLastBrightnessY();
    }

    /** Packs lightmap coordinates into the 32-bit attribute value the FFP vertex path consumes. */
    public static int packBrightness(float x, float y) {
        return ((int) y << 16) | ((int) x & 0xFFFF);
    }

    /** Lightmap coordinates of the last {@code setLightmapTextureCoords} call, packed as an attribute value. */
    public static int packedLastBrightness() {
        final GLContextState glCtx = GLStateManager.ctx();
        return packBrightness(glCtx.lastBrightnessX, glCtx.lastBrightnessY);
    }

    /**
     * Forces every depth write off. The deferred entity overlay replay sets it for the duration of
     * one replayed layer, so the layer and any subclass it calls cannot turn depth writes back on;
     * a layer that issues {@code depthMask(true)} itself would otherwise defeat the replay.
     */
    public static boolean forceDepthMaskOff;

    /** Whether recorded vertex formats are expected to carry the extended attributes. */
    public static boolean extendedAttribsExpected;

    /** Whether recorded vertex data is widened to the extended attribute layout. */
    public static volatile boolean expandVertexFormats;

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

    /**
     * Threads the GLSM worker pool may use. Zero lets the pool size itself from the core count.
     */
    public static volatile int workerThreadCount;

    /** Maximum submitted frames retained by the SDL GPU device, fixed before device creation. */
    public static int sdlGpuFramesInFlight = 2;

    /** Whether SDL presentation runs on a dedicated executor. Requires a client restart to change. */
    public static boolean sdlGpuPresenterThread = true;

    private GLSMConfig() {}
}
