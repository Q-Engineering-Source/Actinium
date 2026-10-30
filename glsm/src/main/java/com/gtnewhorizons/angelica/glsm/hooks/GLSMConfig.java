package com.gtnewhorizons.angelica.glsm.hooks;

public final class GLSMConfig {

    // Lightmap (replaces OpenGlHelper.lastBrightnessX/Y)
    public static float lastBrightnessX;
    public static float lastBrightnessY;

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

    private GLSMConfig() {}
}
