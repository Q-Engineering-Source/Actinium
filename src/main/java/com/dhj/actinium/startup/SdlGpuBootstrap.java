package com.dhj.actinium.startup;

import com.dhj.actinium.config.ActiniumConfig;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.backend.BackendStartGuard;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUDisplayBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.Drawable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;
import org.lwjgl.opengl.SharedDrawable;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;

import java.io.File;
import java.io.IOException;

/** Reads the renderer choice before window creation and isolates optional Outlands classes. */
public final class SdlGpuBootstrap {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final boolean SUPPORTED = hasResource("net/minecraftforge/client/render/RenderBackendSelection.class")
        && hasResource("org/lwjgl/opengl/DisplayEvents.class") && hasResource("org/lwjgl/MainThread.class")
        && hasResource("org/lwjgl/sdl/SDLGPU.class");
    private static boolean initialized;
    private static boolean requested;

    private SdlGpuBootstrap() {}

    private static boolean hasResource(String path) {
        return SdlGpuBootstrap.class.getClassLoader().getResource(path) != null;
    }

    public static boolean isSupported() { return SUPPORTED; }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        var options = ActiniumRuntime.options();
        var advanced = options.advanced;
        boolean guardTripped = BackendStartGuard.begin(new File("config/actinium"), advanced.renderBackendSdlGpu,
            SystemProperties.SDL_GPU_OVERRIDE != null, SDLGPUGate.REQUIREMENTS_REVISION,
            BackendOptions.sdlGpuDriver(advanced.renderBackendDriver));
        if (guardTripped) {
            advanced.renderBackendSdlGpu = false;
            try {
                SodiumGameOptions.writeToDisk(options);
            } catch (IOException failure) {
                LOGGER.error("Could not save the OpenGL backend selection after repeated SDL startup failures", failure);
            }
        }
        BackendOptions.latch(advanced.renderBackendSdlGpu, advanced.renderBackendDriver, ActiniumConfig.glVersion);
        GLSMConfig.sdlGpuFramesInFlight = advanced.sdlGpuFramesInFlight;
        GLSMConfig.sdlGpuPresenterThread = advanced.sdlGpuPresenterThread;
        requested = BackendOptions.sdlGpuRequested();
        if (!requested) return;
        if (SUPPORTED) {
            Outlands.initialize();
        } else {
            LOGGER.warn("SDL GPU requires Outlands with the render backend API; using OpenGL for this launch");
        }
    }

    public static boolean isActive() { return requested && SUPPORTED && Outlands.isActive(); }

    public static void onLoadingComplete() {
        if (isActive()) BackendStartGuard.latch();
    }

    public static boolean createDisplay(PixelFormat format) {
        initialize();
        if (!requested || !SUPPORTED || !Outlands.isSelected()) return false;
        Display.create(format);
        return true;
    }

    public static boolean isSharedDrawable(Object drawable) {
        return isActive() && Outlands.isSharedDrawable(drawable);
    }

    public static void makeCurrent(Drawable drawable) throws LWJGLException { Outlands.makeCurrent(drawable); }
    public static void releaseContext(Drawable drawable) throws LWJGLException { Outlands.releaseContext(drawable); }
    public static boolean isCurrent(Drawable drawable) { return Outlands.isCurrent(drawable); }
    public static void destroySharedDrawable(SharedDrawable drawable) throws LWJGLException { Outlands.destroySharedDrawable(drawable); }
    public static void present() { Outlands.present(); }
    public static void setSwapInterval(int interval) { Outlands.setSwapInterval(interval); }
    public static String adapter() { return Outlands.adapter(); }
    public static String version() { return Outlands.version(); }
    public static void checkGLError() { GLStateManager.checkGLError(); }
    public static void initContext(float red, float green, float blue) {
        GLStateManager.glClearColor(red, green, blue, 0.0f);
        GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private static final class Outlands {
        private static void initialize() { SDLGPUGate.registerBackend(); }
        private static boolean isActive() { return SDLGPUGate.isActive(); }
        private static boolean isSelected() { return SDLGPUGate.isSelected(); }
        private static boolean isSharedDrawable(Object drawable) { return SDLGPUDisplayBridge.isSdlSharedDrawable(drawable); }
        private static void makeCurrent(Drawable drawable) throws LWJGLException { SDLGPUDisplayBridge.makeCurrent(drawable); }
        private static void releaseContext(Drawable drawable) throws LWJGLException { SDLGPUDisplayBridge.releaseContext(drawable); }
        private static boolean isCurrent(Drawable drawable) { return SDLGPUDisplayBridge.isCurrent(drawable); }
        private static void destroySharedDrawable(SharedDrawable drawable) throws LWJGLException { SDLGPUDisplayBridge.destroySharedDrawable(drawable); }
        private static void present() { SDLGPUDisplayBridge.present(); }
        private static void setSwapInterval(int interval) { SDLGPUDisplayBridge.setSwapInterval(interval); }
        private static String adapter() { return SDLGPUDisplayBridge.adapter(); }
        private static String version() { return SDLGPUDisplayBridge.version(); }
    }
}
