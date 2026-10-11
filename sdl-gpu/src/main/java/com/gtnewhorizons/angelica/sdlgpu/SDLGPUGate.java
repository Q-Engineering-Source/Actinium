package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLLog;
import org.lwjglx.Sys;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.List;
import java.util.function.Consumer;

/**
 * Gate owning the SDL GPU device lifecycle and availability probing.
 *
 * <p>The window takeover path is currently removed: Cleanroom's lwjglxx always creates a GLFW
 * window with a GL context, which a GPU device cannot claim ({@code VK_ERROR_NATIVE_WINDOW_IN_USE_KHR}),
 * and the interim workaround (building a {@code GLFW_NO_API} window and mirroring lwjglxx's
 * {@code Display} state with hand-installed GLFW callbacks) was reverted. The backend is therefore
 * not registered in the {@code RenderBackend} service file. Re-wiring the backend requires a window
 * source Cleanroom actually controls (a native SDL window) whose handle can be wrapped and claimed
 * through {@link Device#claimWindow} before the backend presents.</p>
 */
public final class SDLGPUGate {

    private static final Logger LOG = LogManager.getLogger("Angelica/SDLGPU");

    private SDLGPUGate() {}

    private static Device device;

    private static volatile Boolean deviceReady;

    public static synchronized Device device() {
        if (device == null) device = new Device();
        return device;
    }

    public static final String LWJGL3IFY_MIN_VERSION = "3.0.25";

    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLGPU.class", "no lwjgl-sdl on the classpath"),
        new Check("/me/eigenraven/lwjgl3ify/api/DisplayEvents.class", "lwjgl3ify older than " + LWJGL3IFY_MIN_VERSION + ", update the mod: https://modrinth.com/mod/lwjgl3ify"),
        new Check("/org/lwjgl/util/shaderc/Shaderc.class", staleInstanceLibraries("lwjgl-shaderc")),
        new Check("/org/lwjgl/util/spvc/Spvc.class", staleInstanceLibraries("lwjgl-spvc")));

    private static final Check MISSING_REQUIREMENT = DependencyVerifier.firstMissing(SDLGPUGate.class, REQUIREMENTS);

    private static String staleInstanceLibraries(String artifact) {
        return "no " + artifact + " on the classpath; the launcher instance library list predates lwjgl3ify " + LWJGL3IFY_MIN_VERSION + ", re-apply lwjgl3ify-<version>-multimc.zip (overwrites mmc-pack.json)";
    }

    public static String missingRequirement() {
        return MISSING_REQUIREMENT == null ? null : MISSING_REQUIREMENT.errorMessage();
    }

    public static boolean isSDLGPUAvailable() {
        return MISSING_REQUIREMENT == null;
    }

    public static boolean isDeviceReady() {
        final Boolean ready = deviceReady;
        return ready != null ? ready : probe();
    }

    private static synchronized boolean probe() {
        if (deviceReady != null) return deviceReady;
        if (!BackendOptions.sdlGpuRequested() || !isSDLGPUAvailable()) {
            deviceReady = false;
            return false;
        }
        boolean ok;
        try {
            Sys.initialize();
            // Backend probing happens during class initialization on the main thread.
            ok = device().createDevice();
        } catch (Throwable t) {
            LOG.error("SDL GPU device probe failed", t);
            ok = false;
        }
        if (!ok) resetSdlLogging();
        deviceReady = ok;
        return ok;
    }

    public static boolean isActive() {
        if (!isSDLGPUAvailable()) return false;
        final RenderBackend rb = BackendManager.RENDER_BACKEND;
        return rb instanceof SDLGPURenderBackend;
    }

    public static void ensureDrawableInstalled() {
        if (!isActive()) return;
        SDLGPUDisplayBridge.ensureDrawableInstalled();
    }

    public static void prewarmSpirv(String transformedSource, int glShaderType) {
        if (!isActive()) return;
        ShaderManager.prewarmSpirv(transformedSource, glShaderType);
    }

    public static void prewarmSpirv(String transformedSource, GLSLParser.Translation_unitContext bodyTree, int headerLen, int glShaderType) {
        if (!isActive()) return;
        ShaderManager.prewarmSpirv(transformedSource, bodyTree, headerLen, glShaderType);
    }

    public static void clearShaderPrewarmCache() {
        if (!isActive()) return;
        ShaderManager.clearPrewarmCache();
    }

    public static Consumer<Object> sdlGpuPreSwapchainInvalidatingCallback() {
        return change -> BackendManager.RENDER_BACKEND.onPreSwapchainInvalidatingChange(change);
    }

    private static void resetSdlLogging() {
        SDLLog.SDL_ResetLogPriorities();
    }
}
