package com.dhj.actinium.mixin.vintage.core.startup;

import com.dhj.actinium.debug.ActiniumStartupDebugConfig;
import com.dhj.actinium.debug.CoreProfileContextAttributes;
import com.dhj.actinium.debug.OpenGlVersion;
import com.dhj.actinium.render.sdlgpu.SdlGpuHostGlCapabilities;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.ForgeEarlyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftCoreProfileDisplay {
    @Unique
    private static final Logger celeritas$LOGGER = LogManager.getLogger("Celeritas");

    @Unique
    private static final int[][] celeritas$SDL_CONTEXT_VERSIONS = { { 4, 6 }, { 4, 5 }, { 3, 3 } };

    @Shadow
    private boolean fullscreen;

    @Inject(method = "createDisplay", at = @At("HEAD"), cancellable = true)
    private void celeritas$createCoreProfileDisplay(CallbackInfo ci) throws LWJGLException {
        final int originalMajor = ForgeEarlyConfig.OPENGL_VERSION_MAJOR;
        final int originalMinor = ForgeEarlyConfig.OPENGL_VERSION_MINOR;
        final boolean originalDebug = ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT;
        try {
            celeritas$createCoreProfileDisplayInner(ci);
        } finally {
            // Don't leave the core-profile request persisted in forge_early.cfg: without Actinium,
            // Cleanroom's default path expects the compatibility profile and fails to create a context.
            // LWJGLXX syncs the request to the file while creating the context, so restore the fields
            // and sync them back (see CoreProfileContextAttributes.persistForgeEarlyCompatProfile).
            CoreProfileContextAttributes.restoreForgeEarlyCompatProfile(originalMajor, originalMinor, originalDebug);
            CoreProfileContextAttributes.persistForgeEarlyCompatProfile();
        }
    }

    /**
     * Creates the display on the SDL GPU path.
     *
     * <p>The bridge replaces the window creation inside {@code Display.create} with a native SDL
     * window, so this calls the same entry point the GL path does and then takes the window the
     * bridge published. No GL context exists afterwards, which is why the core-profile loop, the
     * {@code GL_VERSION} validation and Forge's window style/taskbar hooks (they resolve the window
     * through the GL context) are all skipped here; the backend owns presentation.</p>
     */
    @Unique
    private void celeritas$createSdlDisplay(CallbackInfo ci) throws LWJGLException {
        Display.setResizable(true);
        Display.setTitle("Cleanroom");
        PixelFormat format = new PixelFormat().withDepthBits(24).withStencilBits(8);
        // The request is only validated against the context the host already created, so any version
        // at or below it is accepted. LWJGLY caps ContextAttribs at GL 4.5, hence 4.5 and the engine
        // floor 3.3 as fallbacks when 4.6 cannot even be constructed or is rejected by the host.
        Exception lastException = null;
        for (int[] version : celeritas$SDL_CONTEXT_VERSIONS) {
            try {
                celeritas$adoptSdlContext(format, version[0], version[1]);
            } catch (IllegalArgumentException | LWJGLException e) {
                lastException = e;
                celeritas$LOGGER.debug(
                    "SDL window host did not accept an OpenGL {}.{} context request",
                    version[0],
                    version[1],
                    e
                );
                continue;
            }
            // The host GL context is still current here; capture its capabilities before the backend
            // claims the window, so vanilla's own probe in initializeTextures can be answered later.
            SdlGpuHostGlCapabilities.capture();
            SDLGPUGate.handOverWindow();
            celeritas$LOGGER.info("SDL GPU display is up; the OpenGL engine paths are replaced by the backend");
            ci.cancel();
            return;
        }
        throw new LWJGLException("The SDL window host accepted no OpenGL 3.3+ context request", lastException);
    }

    /**
     * lwjglx's {@code Display.create} declares no checked exception, so the throws clause here keeps
     * {@link LWJGLException} reachable for the caller's catch on every compile classpath.
     */
    @Unique
    private static void celeritas$adoptSdlContext(PixelFormat format, int major, int minor) throws LWJGLException {
        Display.create(format, new ContextAttribs(major, minor));
    }

    @Unique
    private void celeritas$createCoreProfileDisplayInner(CallbackInfo ci) throws LWJGLException {
        // SDL GPU: a GPU device cannot claim the window lwjglx would create with a GL context, so the
        // window and the whole display lifecycle are handed to the bridge
        // (see docs/research/sdl-gpu-enable-path.md). isHandoverAvailable() is false whenever the SDL
        // backend was not requested or is not usable, so the GL path below stays byte-identical.
        if (SDLGPUGate.isHandoverAvailable()) {
            celeritas$createSdlDisplay(ci);
            return;
        }

        Display.setResizable(true);
        Display.setTitle("Cleanroom");

        PixelFormat format = new PixelFormat().withDepthBits(24).withStencilBits(8);
        int maxMajor = 4;
        boolean macos = LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX;
        int maxMinor = macos ? 1 : 6;
        boolean lwjglDebug = ActiniumStartupDebugConfig.enableLwjglDebug();
        Exception lastException = null;

        for (int major = maxMajor; major >= 3; --major) {
            int startMinor = major == 4 ? maxMinor : 3;
            int endMinor = major == 3 ? 3 : 0;

            for (int minor = startMinor; minor >= endMinor; --minor) {
                // LWJGLXX ignores ContextAttribs and reads these fields instead, so keep them in sync on every platform.
                CoreProfileContextAttributes.applyForgeEarlyCoreProfile(major, minor, lwjglDebug);
                try {
                    // LWJGLY 2.0 caps ContextAttribs at GL 4.5 and throws on construction, so a rejected
                    // version must fall through to the next candidate exactly like a driver failure.
                    ContextAttribs attribs = CoreProfileContextAttributes.create(major, minor, lwjglDebug);
                    celeritas$createDisplay(format, attribs);
                } catch (Exception e) {
                    lastException = e;
                    celeritas$LOGGER.debug(
                        "Failed to create requested OpenGL {}.{} core profile context (debug={})",
                        major,
                        minor,
                        lwjglDebug,
                        e
                    );
                    celeritas$destroyDisplayAfterFailure();
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
                    lastException = celeritas$createContextValidationFailure(e);
                    celeritas$LOGGER.warn(
                        "Created requested OpenGL {}.{} core profile context, but actual GL_VERSION is unusable: {}",
                        major,
                        minor,
                        actualVersionString,
                        e
                    );
                    celeritas$destroyDisplayAfterFailure();
                    continue;
                }

                celeritas$LOGGER.info(
                    "Created OpenGL core profile context: requested={}.{} (debug={}), actual={}.{} ({})",
                    major,
                    minor,
                    lwjglDebug,
                    actualVersion.major(),
                    actualVersion.minor(),
                    actualVersionString
                );
                ForgeHooksClient.initializeWindowsInformation();
                ForgeHooksClient.setWindowStyle(this.fullscreen);
                ForgeHooksClient.initializeTaskbarAPI();
                ci.cancel();
                return;
            }
        }

        throw new LWJGLException("Failed to create an OpenGL 3.3+ core profile context", lastException);
    }

    @Unique
    private static Exception celeritas$createContextValidationFailure(RuntimeException e) {
        // Keep the return type as Exception so CleanMix can still resolve the lastException frame during transformation.
        return new LWJGLException("Created context does not provide valid OpenGL 3.3+", e);
    }

    @Unique
    private static void celeritas$createDisplay(PixelFormat format, ContextAttribs attribs) throws LWJGLException {
        Display.create(format, attribs);
        if (!Display.isCreated()) {
            throw new LWJGLException("Display.create returned without creating an OpenGL context");
        }
    }

    @Unique
    private static void celeritas$destroyDisplayAfterFailure() {
        try {
            Display.destroy();
        } catch (RuntimeException destroyFailure) {
            celeritas$LOGGER.warn("Failed to destroy an unsuccessful OpenGL context", destroyFailure);
        }
    }
}
