package com.dhj.actinium.startup;

import com.dhj.actinium.debug.ActiniumStartupDebugConfig;
import com.dhj.actinium.debug.CoreProfileContextAttributes;
import com.dhj.actinium.debug.OpenGlVersion;
import com.gtnewhorizons.angelica.config.GLVersionChoice;
import com.gtnewhorizons.angelica.config.SystemProperties;
import net.minecraftforge.common.ForgeEarlyConfig;
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

    private CoreProfileDisplayCreator() {
    }

    /**
     * Tries supported core-profile versions until one creates a usable OpenGL context.
     *
     * @throws LWJGLException if no attempted profile creates an OpenGL 3.3 or newer context
     */
    public static void create(PixelFormat format) throws LWJGLException {
        final int originalMajor = ForgeEarlyConfig.OPENGL_VERSION_MAJOR;
        final int originalMinor = ForgeEarlyConfig.OPENGL_VERSION_MINOR;
        final boolean originalDebug = ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT;
        boolean lwjglDebug = ActiniumStartupDebugConfig.enableLwjglDebug();
        try {
            createWithFallback(format, lwjglDebug);
        } finally {
            // LWJGLXX persists the requested profile while creating the context. Restore and sync the
            // compatibility profile so later launches without Actinium keep Cleanroom's default path.
            CoreProfileContextAttributes.restoreForgeEarlyCompatProfile(originalMajor, originalMinor, originalDebug);
            CoreProfileContextAttributes.persistForgeEarlyCompatProfile();
        }
    }

    private static void createWithFallback(PixelFormat format, boolean lwjglDebug) throws LWJGLException {
        final boolean macos = LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX;
        final GLVersionChoice requested = GLVersionChoice.of(parseGlProfile(), macos ? 41 : 46);
        final int maxMajor = requested == GLVersionChoice.AUTO ? 4 : requested.version() / 10;
        final int maxMinor = requested == GLVersionChoice.AUTO ? (macos ? 1 : 6) : requested.version() % 10;
        Exception lastException = null;
        String lastRejection = null;

        for (int major = maxMajor; major >= 3; --major) {
            int startMinor = major == 4 ? maxMinor : 3;
            int endMinor = major == 3 ? 3 : 0;

            for (int minor = startMinor; minor >= endMinor; --minor) {
                // LWJGLXX ignores ContextAttribs and reads these fields instead, so keep them in sync on every platform.
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
                OpenGlVersion actualVersion;
                try {
                    actualVersionString = GL11.glGetString(GL11.GL_VERSION);
                    actualVersion = OpenGlVersion.parse(actualVersionString);
                    if (!actualVersion.isAtLeast(3, 3)) {
                        throw new IllegalStateException(
                            "OpenGL 3.3 or newer is required, but the created context reports " + actualVersion
                        );
                    }
                } catch (RuntimeException e) {
                    boolean coreProfile = (GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK)
                        & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0;
                    String renderer = GL11.glGetString(GL11.GL_RENDERER);
                    lastRejection = "requested OpenGL " + major + "." + minor + " core profile, driver returned \""
                        + actualVersionString + "\" (core profile: " + coreProfile + ", GPU: " + renderer + ")";
                    lastException = createContextValidationFailure(e);
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

        StringBuilder failureMessage = new StringBuilder(
            "Actinium requires an OpenGL 3.3+ core profile context and could not create one."
        );
        if (lastRejection != null) {
            failureMessage.append(" Last driver answer: ").append(lastRejection).append('.');
        }
        if (lastException != null) {
            failureMessage.append(" Last error: ").append(lastException.getMessage()).append('.');
        }
        failureMessage.append(" Update or install the GPU driver.");
        LOGGER.error("FATAL: {}", failureMessage);
        throw new LWJGLException(failureMessage.toString(), lastException);
    }

    private static Exception createContextValidationFailure(RuntimeException e) {
        // Keep the return type as Exception so CleanMix can still resolve the lastException frame during transformation.
        return new LWJGLException("Created context does not provide valid OpenGL 3.3+", e);
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
     * Reads {@link SystemProperties#GL_PROFILE} as a {@code major.minor} version. Returns 0 for an
     * empty or unparsable value, which {@link GLVersionChoice#of} resolves to
     * {@link GLVersionChoice#AUTO}.
     */
    private static int parseGlProfile() {
        final String raw = SystemProperties.GL_PROFILE.trim();
        if (raw.isEmpty()) {
            return 0;
        }
        final int dot = raw.indexOf('.');
        try {
            if (dot < 0) {
                return Integer.parseInt(raw) * 10;
            }
            final int major = Integer.parseInt(raw.substring(0, dot));
            final String minorPart = raw.substring(dot + 1);
            return major * 10 + (minorPart.isEmpty() ? 0 : Integer.parseInt(minorPart));
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring unparsable angelica.glProfile value '{}'", raw);
            return 0;
        }
    }
}
