package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.backend.BackendStartGuard;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackendCandidates;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.device.SDLDrawable;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import net.minecraftforge.client.render.RenderBackend;
import net.minecraftforge.client.render.RenderBackendSelection;
import net.minecraftforge.client.render.RenderBackends;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayEvents;
import org.lwjgl.sdl.SDLLog;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.List;

import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_METAL;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_VULKAN;

/** Selects SDL GPU before Outlands freezes the window backend. */
public final class SDLGPUGate {
    private static final Logger LOG = LogManager.getLogger("Actinium/SDLGPU");
    private static final String BACKEND_ID = "actinium-sdl-gpu";
    public static final int REQUIREMENTS_REVISION = 2;
    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLGPU.class", "SDL GPU bindings are missing"),
        new Check("/org/lwjgl/MainThread.class", "Outlands main-thread API is missing"),
        new Check("/org/lwjgl/opengl/DisplayEvents.class", "Outlands window lifecycle callbacks are missing"),
        new Check("/net/minecraftforge/client/render/RenderBackendSelection.class", "Outlands render backend API is missing"),
        new Check("/org/lwjgl/util/shaderc/Shaderc.class", "LWJGL shaderc bindings are missing"),
        new Check("/org/lwjgl/util/spvc/Spvc.class", "LWJGL SPIRV-Cross bindings are missing"));
    private static final Check MISSING_REQUIREMENT = DependencyVerifier.firstMissing(SDLGPUGate.class, REQUIREMENTS);
    private static Device device;
    private static Boolean deviceReady;
    private static boolean registered;
    private static volatile boolean engaged;

    private SDLGPUGate() {}

    public static synchronized Device device() {
        if (device == null) device = new Device();
        return device;
    }

    public static String missingRequirement() {
        return MISSING_REQUIREMENT == null ? null : MISSING_REQUIREMENT.errorMessage();
    }

    public static boolean isSDLGPUAvailable() { return MISSING_REQUIREMENT == null; }

    public static synchronized boolean isDeviceReady() {
        if (deviceReady != null) return deviceReady;
        if (!BackendOptions.sdlGpuRequested() || !isSDLGPUAvailable()) {
            BackendStartGuard.disarm();
            return deviceReady = false;
        }
        Device target = device();
        BackendStartGuard.arm();
        try {
            Sys.initialize();
            deviceReady = MainThread.runOnMainThread(target::createDevice);
        } catch (RuntimeException | LinkageError failure) {
            LOG.error("SDL GPU device probe failed", failure);
            deviceReady = false;
        }
        if (!deviceReady) {
            MainThread.runOnMainThread(target::destroyDevice);
            SDLLog.SDL_ResetLogPriorities();
            BackendStartGuard.disarm();
        }
        return deviceReady;
    }

    public static synchronized void registerBackend() {
        if (registered) return;
        registered = true;
        if (!isDeviceReady()) {
            LOG.warn("SDL GPU is unavailable: {}", missingRequirement() == null ? "device creation failed" : missingRequirement());
            return;
        }
        RenderBackend backend = new OutlandsBackend();
        RenderBackends.register(backend);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(backend, () -> Boolean.TRUE.equals(deviceReady), 100));
        RenderBackendCandidates.register(SDLGPURenderBackend::new);
        DisplayEvents.addPostWindowCreateListener(context -> {
            if (!isSelected()) return;
            onWindowCreated(context.window());
            Display.setDrawable(new SDLDrawable());
        });
        DisplayEvents.addPreWindowDestroyListener(context -> beforeWindowDestroy());
    }

    public static boolean isSelected() {
        return registered && RenderBackendSelection.get().id().equals(BACKEND_ID);
    }

    public static boolean isEngaged() { return engaged; }

    public static boolean isActive() {
        return engaged && BackendManager.RENDER_BACKEND.isSDLGPU();
    }

    public static void onWindowCreated(long window) {
        if (!isSelected() || engaged) return;
        WindowBackends.useSdl();
        Device target = device();
        MainThread.runOnMainThread(() -> target.claimWindow(window));
        engaged = true;
        SDLGPULWJGLService.bind();
        BackendManager.RENDER_BACKEND.onPostWindowCreate(window);
    }

    public static void beforeWindowDestroy() {
        if (!engaged) return;
        Device target = device();
        try {
            BackendManager.shutdown();
        } finally {
            MainThread.runOnMainThread(target::destroyDevice);
            SDLHostHooks.clearCapabilities();
            SDLGPUDisplayBridge.clearCurrentDrawable();
            engaged = false;
        }
    }

    public static void ensureDrawableInstalled() {
        if (isActive()) SDLGPUDisplayBridge.ensureDrawableInstalled();
    }

    public static void prewarmSpirv(String source, int type) {
        if (isActive()) ShaderManager.prewarmSpirv(source, type);
    }

    public static void prewarmSpirv(String source, GLSLParser.Translation_unitContext tree, int headerLength, int type) {
        if (isActive()) ShaderManager.prewarmSpirv(source, tree, headerLength, type);
    }

    public static void clearShaderPrewarmCache() {
        if (isActive()) ShaderManager.clearPrewarmCache();
    }

    private static final class OutlandsBackend implements RenderBackend {
        @Override public String id() { return BACKEND_ID; }

        @Override public long sdlWindowFlags() {
            if (device().isMetal()) return SDL_WINDOW_METAL;
            return "vulkan".equalsIgnoreCase(device().getDriverName()) ? SDL_WINDOW_VULKAN : 0L;
        }

        @Override public void init(long window) { onWindowCreated(window); }
        @Override public void close() { beforeWindowDestroy(); }
        @Override public Object nativeContext() { return device().getDevice(); }
    }
}
