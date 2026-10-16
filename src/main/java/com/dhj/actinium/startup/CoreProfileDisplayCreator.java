package com.dhj.actinium.startup;

import com.dhj.actinium.debug.ActiniumStartupDebugConfig;
import com.dhj.actinium.debug.CoreProfileContextAttributes;
import com.dhj.actinium.debug.OpenGlVersion;
import net.minecraftforge.common.ForgeEarlyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

/**
 * Creates a core-profile display while leaving Minecraft's surrounding startup method intact.
 */
public final class CoreProfileDisplayCreator {
    /** Logger used for rejected context candidates and display cleanup failures. */
    private static final Logger LOGGER = LogManager.getLogger("Celeritas");

    /** Prevents instantiation because display creation is a stateless startup operation. */
    private CoreProfileDisplayCreator() {
    }

    /**
     * Tries supported core-profile versions until one creates a usable OpenGL context.
     *
     * @param format pixel format requested by Minecraft, with the stencil buffer enabled
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

    /** Attempts supported profiles in descending version order and validates the resulting context. */
    private static void createWithFallback(PixelFormat format, boolean lwjglDebug) throws LWJGLException {
        int maxMajor = 4;
        boolean macos = LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX;
        int maxMinor = macos ? 1 : 6;
        Exception lastException = null;

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

        throw new LWJGLException("Failed to create an OpenGL 3.3+ core profile context", lastException);
    }

    /** Wraps a runtime GL version parsing failure in the checked failure type expected by startup. */
    private static Exception createContextValidationFailure(RuntimeException e) {
        // Keep the return type as Exception so CleanMix can still resolve the lastException frame during transformation.
        return new LWJGLException("Created context does not provide valid OpenGL 3.3+", e);
    }

    /** Creates one candidate display and rejects implementations that return without a window. */
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
}
