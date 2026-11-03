package com.gtnewhorizons.angelica.config;

/**
 * JVM properties that switch GLSM away from its default behaviour. These are read before the
 * game window exists, so the class only wraps {@link Boolean#getBoolean} and friends; anything
 * that needs the game configuration belongs in the option screens instead.
 */
public final class SystemProperties {

    private SystemProperties() {}

    /**
     * Forces the streaming vertex path onto the orphan pattern. Set to {@code true} to rule the
     * persistent ring out when diagnosing driver behaviour.
     */
    public static final boolean FORCE_ORPHAN_STREAMING = Boolean.getBoolean("angelica.debug.forceOrphanStreaming");

    /**
     * Core profile version to request, written as {@code major.minor} (for example {@code 4.5}).
     * Empty or unparsable leaves the choice to the probe over the platform ceiling.
     */
    public static final String GL_PROFILE = System.getProperty("angelica.glProfile", "");

    /**
     * Forces the SDL GPU render backend on or off, overriding the configuration value. Null when
     * unset, so the configuration value decides.
     */
    public static final Boolean SDL_GPU_OVERRIDE = parseBooleanOverride("angelica.sdlgpu.enable");

    /** Overrides the SDL GPU driver chosen by the configuration value. Null when unset. */
    public static final String SDL_GPU_DRIVER_OVERRIDE = parseStringOverride("angelica.sdlgpu.driver");

    private static Boolean parseBooleanOverride(String key) {
        final String raw = System.getProperty(key);
        return raw == null || raw.isEmpty() ? null : Boolean.parseBoolean(raw);
    }

    private static String parseStringOverride(String key) {
        final String raw = System.getProperty(key);
        return raw == null || raw.isEmpty() ? null : raw;
    }
}
