package com.dhj.actinium.startup;

import com.dhj.actinium.compat.ForgeEarlyConfigCompat;
import com.dhj.actinium.debug.ActiniumStartupDebugConfig;
import com.dhj.actinium.debug.CoreProfileContextAttributes;
import com.dhj.actinium.debug.OpenGlVersion;
import com.gtnewhorizons.angelica.config.GLVersionChoice;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.PixelFormat;

/**
 * Creates a core-profile display while leaving Minecraft's surrounding startup method intact.
 */
public final class CoreProfileDisplayCreator {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas");

    /** Lowest core profile version Actinium runs on; the fallback scan cannot go below it. */
    private static final int REQUIRED_MAJOR = RenderSystem.MIN_CORE_VERSION / 10;
    private static final int REQUIRED_MINOR = RenderSystem.MIN_CORE_VERSION % 10;
    private static final String REQUIRED_VERSION = REQUIRED_MAJOR + "." + REQUIRED_MINOR;

    private CoreProfileDisplayCreator() {
    }

    /**
     * Tries supported core-profile versions until one creates a usable OpenGL context.
     *
     * @throws LWJGLException if no attempted profile creates an OpenGL 3.3 or newer context
     */
    public static void create(PixelFormat format) throws LWJGLException {
        ForgeEarlyConfigCompat.Snapshot original = ForgeEarlyConfigCompat.snapshot();
        boolean lwjglDebug = ActiniumStartupDebugConfig.enableLwjglDebug();
        Throwable creationFailure = null;
        try {
            createWithFallback(format, lwjglDebug);
        } catch (LWJGLException | RuntimeException | Error failure) {
            creationFailure = failure;
            throw failure;
        } finally {
            try {
                CoreProfileContextAttributes.restoreForgeEarlyCompatProfile(original.major(), original.minor(), original.debugContext());
                CoreProfileContextAttributes.persistForgeEarlyCompatProfile();
            } catch (RuntimeException | Error restoreFailure) {
                if (creationFailure == null) {
                    throw restoreFailure;
                }
                creationFailure.addSuppressed(restoreFailure);
            }
        }
    }

    private static void createWithFallback(PixelFormat format, boolean lwjglDebug) throws LWJGLException {
        final boolean macos = LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX;
        final GLVersionChoice requested = GLVersionChoice.of(requestedGlVersion(), macos ? 41 : 46);
        final int maxMajor = requested == GLVersionChoice.AUTO ? 4 : requested.version() / 10;
        final int maxMinor = requested == GLVersionChoice.AUTO ? (macos ? 1 : 6) : requested.version() % 10;
        int maxProbe = maxMajor * 10 + maxMinor;
        Exception lastException = null;
        String lastRejection = null;

        for (int major = maxMajor; major >= REQUIRED_MAJOR; --major) {
            int startMinor = major == 4 ? maxMinor : REQUIRED_MINOR;
            int endMinor = major == REQUIRED_MAJOR ? REQUIRED_MINOR : 0;

            for (int minor = startMinor; minor >= endMinor; --minor) {
                // The configured version only narrows where the scan starts; a rejected context lowers
                // the ceiling so the remaining versions are still probed downwards from it.
                if (major * 10 + minor > maxProbe) continue;
                // Legacy LWJGLXX reads ForgeEarlyConfig instead of ContextAttribs.
                CoreProfileContextAttributes.applyForgeEarlyCoreProfile(major, minor, lwjglDebug);
                ContextAttribs attribs = CoreProfileContextAttributes.create(major, minor, lwjglDebug);
                try {
                    createDisplay(format, attribs);
                } catch (Exception e) {
                    lastException = e;
                    LOGGER.debug(
                        "Failed to create requested OpenGL {}.{} core profile context (debug={})",
                        major,
                        minor,
                        lwjglDebug,
                        e
                    );
                    destroyDisplayAfterFailure();
                    continue;
                }

                String actualVersionString = null;
                int actualVersionPacked = -1;
                OpenGlVersion actualVersion;
                try {
                    actualVersionString = GL11.glGetString(GL11.GL_VERSION);
                    actualVersion = OpenGlVersion.parse(actualVersionString);
                    actualVersionPacked = actualVersion.major() * 10 + actualVersion.minor();
                    if (!actualVersion.isAtLeast(REQUIRED_MAJOR, REQUIRED_MINOR)) {
                        throw new IllegalStateException(
                            "OpenGL " + REQUIRED_VERSION + " or newer is required, but the created context reports "
                                + actualVersion
                        );
                    }
                } catch (RuntimeException e) {
                    boolean coreProfile = (GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK)
                        & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0;
                    String renderer = GL11.glGetString(GL11.GL_RENDERER);
                    lastRejection = describeRejection(major, minor, actualVersionString, coreProfile, renderer);
                    lastException = createContextValidationFailure(e);
                    maxProbe = Math.min(
                        maxProbe,
                        RenderSystem.probeCapAfterRejection(major * 10 + minor, actualVersionPacked)
                    );
                    LOGGER.warn(
                        "Created requested OpenGL {}.{} core profile context, but actual GL_VERSION is unusable: {}",
                        major,
                        minor,
                        actualVersionString,
                        e
                    );
                    destroyDisplayAfterFailure();
                    continue;
                }

                LOGGER.info(
                    "Created OpenGL core profile context: requested={}.{} (debug={}), actual={}.{} ({})",
                    major,
                    minor,
                    lwjglDebug,
                    actualVersion.major(),
                    actualVersion.minor(),
                    actualVersionString
                );
                return;
            }
        }

        final String failureMessage = describeFailure(lastRejection, lastException);
        LOGGER.error("FATAL: {}", failureMessage);
        throw new LWJGLException(failureMessage, lastException);
    }

    /**
     * Assembles the startup failure text. Each part is only appended when the corresponding attempt
     * recorded it, so a driver that never created a context reports its error alone.
     *
     * @param lastRejection description from {@link #describeRejection}, or null when no context was created
     * @param lastException failure from the last attempt, or null when every attempt produced a context
     */
    static String describeFailure(String lastRejection, Exception lastException) {
        final StringBuilder message = new StringBuilder(
            "Actinium requires an OpenGL " + REQUIRED_VERSION + " core profile context and could not create one."
        );
        if (lastRejection != null) {
            message.append(" Last driver answer: ").append(lastRejection).append('.');
        }
        if (lastException != null) {
            message.append(" Last error: ").append(lastException.getMessage()).append('.');
        }
        return message.append(" Update or install the GPU driver.").toString();
    }

    /**
     * Builds the driver-answer line quoted by the startup failure. It repeats the minimum version next to
     * what the driver answered, because a report below the requirement is what makes the fallback scan
     * skip versions that cannot work.
     *
     * @param driverVersion value of {@code GL_VERSION} on the created context, or null when unreadable
     */
    static String describeRejection(
        int major,
        int minor,
        String driverVersion,
        boolean coreProfile,
        String renderer
    ) {
        return "requested OpenGL " + major + '.' + minor + " core profile, driver returned \"" + driverVersion
            + "\" (core profile: " + coreProfile + ", GPU: " + renderer + "); OpenGL " + REQUIRED_VERSION
            + " core or newer is required";
    }

    private static Exception createContextValidationFailure(RuntimeException e) {
        // Keep the return type as Exception so CleanMix can still resolve the lastException frame during transformation.
        return new LWJGLException("Created context does not provide valid OpenGL " + REQUIRED_VERSION, e);
    }

    private static void createDisplay(PixelFormat format, ContextAttribs attribs) throws LWJGLException {
        Display.create(format, attribs);
        if (!Display.isCreated()) {
            throw new LWJGLException("Display.create returned without creating an OpenGL context");
        }
    }

    /** Logs cleanup errors while allowing profile fallback to continue. */
    private static void destroyDisplayAfterFailure() {
        try {
            Display.destroy();
        } catch (RuntimeException destroyFailure) {
            LOGGER.warn("Failed to destroy an unsuccessful OpenGL context", destroyFailure);
        }
    }

    /**
     * Resolves the core profile version to request. {@link SystemProperties#GL_PROFILE} wins when it
     * parses, otherwise the configuration value recorded by {@link BackendOptions} decides. Zero
     * leaves the choice to the probe, which {@link GLVersionChoice#of} resolves to
     * {@link GLVersionChoice#AUTO}.
     */
    private static int requestedGlVersion() {
        final String raw = SystemProperties.GL_PROFILE.trim();
        if (raw.isEmpty()) {
            return BackendOptions.configGlVersion();
        }
        final int parsed = parseGlProfile(raw);
        if (parsed >= 0) {
            return parsed;
        }
        LOGGER.warn("Ignoring unparsable angelica.glProfile value '{}'", raw);
        return BackendOptions.configGlVersion();
    }

    /**
     * Parses an {@code angelica.glProfile} value into the packed version the probe expects.
     *
     * @return the packed version, or -1 when the value is not a number or {@code major.minor} pair
     */
    static int parseGlProfile(String raw) {
        final int dot = raw.indexOf('.');
        try {
            if (dot < 0) {
                return Integer.parseInt(raw) * 10;
            }
            final int major = Integer.parseInt(raw.substring(0, dot));
            final String minorPart = raw.substring(dot + 1);
            return major * 10 + (minorPart.isEmpty() ? 0 : Integer.parseInt(minorPart));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
