package com.gtnewhorizons.angelica.config;

import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import net.minecraft.launchwrapper.Launch;

import java.nio.file.Path;

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

    /** Backend override shared with the early window selection. */
    public static final String KEY_USE_SDL_GPU = "angelica.sdlgpu.enable";

    /** Existing LWJGL service override consumed by Actinium's embedded renderer. */
    public static final String KEY_CELERITAS_LWJGL_SERVICE = "actinium.lwjglService";

    /** LWJGL's native binding debug setting, also used when creating a GPU device. */
    public static final boolean LWJGL_DEBUG = Boolean.getBoolean("org.lwjgl.util.Debug");

    /** Directory used to extract bundled MoltenVK libraries on macOS. */
    public static final String MOLTENVK_DIR = "actinium/natives/moltenvk";

    /** Shader diagnostics follow the in-game GLSM debug setting. */
    public static boolean dumpShaders() { return GLSMDebug.isEnabled(); }

    /** A diagnostic phase writes its translated sources into this directory when debugging is enabled. */
    public static Path shaderDumpDir(String phase) {
        return dumpShaders() ? Path.of("actinium_dumps", phase) : null;
    }

    /** Reports the mapping environment supplied by the loader. */
    public static boolean isDeobf() {
        return Boolean.TRUE.equals(Launch.blackboard.get("fml.deobfuscatedEnvironment"));
    }

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
