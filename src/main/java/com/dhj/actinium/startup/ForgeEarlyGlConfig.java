package com.dhj.actinium.startup;

import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.common.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Applies the core-profile request to the loader's early OpenGL configuration.
 *
 * <p>Cleanroom's display shims read the requested version, profile and debug flag from {@code ForgeEarlyConfig}
 * instead of {@link org.lwjgl.opengl.ContextAttribs}, so the request has to be written there before the context is
 * created. Outlands' SDL shim takes the same values from the attributes and no longer declares those fields, which
 * leaves this class unavailable and the request in the attributes only.</p>
 */
public final class ForgeEarlyGlConfig {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas");
    private static final boolean AVAILABLE = probe();

    private final int originalMajor;
    private final int originalMinor;
    private final boolean originalDebugContext;

    private ForgeEarlyGlConfig(int originalMajor, int originalMinor, boolean originalDebugContext) {
        this.originalMajor = originalMajor;
        this.originalMinor = originalMinor;
        this.originalDebugContext = originalDebugContext;
    }

    /** Returns whether the running loader still takes its early OpenGL settings from {@code ForgeEarlyConfig}. */
    public static boolean available() {
        return AVAILABLE;
    }

    /** Records the loader's current early OpenGL settings so {@link #restore()} can put them back. */
    public static ForgeEarlyGlConfig capture() {
        return new ForgeEarlyGlConfig(
            ForgeEarlyConfig.OPENGL_VERSION_MAJOR,
            ForgeEarlyConfig.OPENGL_VERSION_MINOR,
            ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT
        );
    }

    /** Requests a core-profile context for the next display creation. */
    public void apply(int major, int minor, boolean debugContext) {
        ForgeEarlyConfig.OPENGL_VERSION_MAJOR = major;
        ForgeEarlyConfig.OPENGL_VERSION_MINOR = minor;
        ForgeEarlyConfig.OPENGL_COMPAT_PROFILE = false;
        ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT = debugContext;
    }

    /**
     * Restores the captured settings in memory.
     */
    public void restore() {
        ForgeEarlyConfig.OPENGL_VERSION_MAJOR = originalMajor;
        ForgeEarlyConfig.OPENGL_VERSION_MINOR = originalMinor;
        // Actinium is the only writer that disables the compatibility profile, so restore it unconditionally.
        ForgeEarlyConfig.OPENGL_COMPAT_PROFILE = true;
        ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT = originalDebugContext;
    }

    /**
     * Restores the captured settings and writes them back to {@code forge_early.cfg}. The shims persist the in-memory
     * settings while they create the context, which leaves the file on the core-profile request; restoring the fields
     * alone would make a later launch without Actinium create a core context whose fixed-pipeline startup code fails.
     */
    public void restoreAndPersist() {
        restore();
        try {
            ConfigManager.sync(ForgeEarlyConfig.class);
        } catch (RuntimeException syncFailure) {
            LOGGER.error("Failed to persist the restored compatibility profile to forge_early.cfg", syncFailure);
        }
    }

    // The fields are the stable signal for "this loader reads its early OpenGL settings from the configuration";
    // loaders whose display shim takes the request from ContextAttribs no longer declare them.
    private static boolean probe() {
        try {
            LOGGER.info(
                "Loader exposes the ForgeEarlyConfig early OpenGL fields (version {}.{}); mirroring the core-profile request there",
                ForgeEarlyConfig.OPENGL_VERSION_MAJOR,
                ForgeEarlyConfig.OPENGL_VERSION_MINOR
            );
            return true;
        } catch (NoSuchFieldError renamedFields) {
            LOGGER.info(
                "Loader no longer exposes ForgeEarlyConfig.OPENGL_VERSION_MAJOR; requesting the core profile through "
                    + "ContextAttribs only"
            );
            return false;
        }
    }
}
