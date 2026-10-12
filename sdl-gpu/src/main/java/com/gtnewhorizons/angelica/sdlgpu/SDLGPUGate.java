package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import com.gtnewhorizons.angelica.sdlgpu.compat.LwjglyLayer;
import com.gtnewhorizons.angelica.sdlgpu.compat.lwjgly.LwjglyWindowHost;
import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLLog;
import org.lwjgl.Sys;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.List;
import java.util.function.Consumer;

/**
 * Gate owning the SDL GPU device lifecycle, availability probing and the window handover.
 *
 * <p>Availability is the conjunction of four things: the SDL GPU backend was requested
 * ({@link BackendOptions#sdlGpuRequested()}), its runtime dependencies are on the classpath, the
 * host runs LWJGLY and has installed the window bridge a GPU device can claim its window from
 * ({@link LwjglyWindowHost} - a GPU device cannot render into a window created for a GL context,
 * which is why the older Cleanroom generation has no SDL route at all), and the GPU device itself
 * could be created. When any of them is missing the backend is not selected and the OpenGL path
 * runs unchanged.</p>
 *
 * <p>When all four hold, {@link #handOverWindow()} is called from the display creation path: it
 * reads the window the host published, claims it for the GPU device and notifies the backend. From
 * that point the process is "engaged" and every GL-specific engine path in Actinium is replaced by
 * the backend's own (see {@code docs/research/sdl-gpu-enable-path.md}).</p>
 */
public final class SDLGPUGate {

    private static final Logger LOG = LogManager.getLogger("Angelica/SDLGPU");

    private SDLGPUGate() {}

    private static Device device;

    private static volatile Boolean deviceReady;

    private static volatile boolean engaged;

    private static volatile boolean unavailableLogged;

    public static synchronized Device device() {
        if (device == null) device = new Device();
        return device;
    }

    /**
     * Runtime prerequisites for the SDL GPU backend.
     *
     * <p>Upstream also requires lwjgl3ify's {@code DisplayEvents}, which is how it suppresses GL
     * context creation. Actinium does not use that API at all - the window comes from the host's
     * LWJGLY window bridge - and lwjgl3ify is not installed in a Cleanroom instance, so requiring it
     * would keep the backend permanently unselectable.</p>
     */
    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLGPU.class", "no lwjgl-sdl on the classpath"),
        new Check("/org/lwjgl/util/shaderc/Shaderc.class", missingSdlLibrary("lwjgl-shaderc")),
        new Check("/org/lwjgl/util/spvc/Spvc.class", missingSdlLibrary("lwjgl-spvc")));

    private static final Check MISSING_REQUIREMENT = DependencyVerifier.firstMissing(SDLGPUGate.class, REQUIREMENTS);

    private static String missingSdlLibrary(String artifact) {
        return "no " + artifact + " on the classpath; the instance is missing part of the SDL GPU "
            + "library set (lwjgl-sdl, lwjgl-shaderc and lwjgl-spvc are all required)";
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

    /**
     * Whether the host runs LWJGLY and has a window the SDL GPU device can claim.
     *
     * <p>The layer check comes first on purpose: on a host without LWJGLY the SDL backend is
     * unavailable no matter what else is present, and short-circuiting there keeps every
     * {@code com.cleanroommc.lwjgly.*} class unloaded on such a run. The actual handle is read at
     * {@link #handOverWindow()} time, not here.</p>
     */
    public static boolean isWindowSupplierPresent() {
        return LwjglyLayer.present() && LwjglyWindowHost.available();
    }

    /**
     * Whether this process can render through SDL GPU: the backend was requested and every
     * prerequisite (runtime dependencies, window supplier, usable GPU device) is in place.
     *
     * <p>This is the availability check the backend selection asks, so an unusable SDL setup is
     * never selected in the first place; the first failure is logged once, with the reason.</p>
     */
    public static boolean isHandoverAvailable() {
        final String reason = unavailableReason();
        if (reason == null) return true;
        // Never warn for the ordinary "this run is not configured for SDL GPU" case: that is every
        // OpenGL start, and the gate would then log a warning into every user's log.
        if (BackendOptions.sdlGpuRequested() && !unavailableLogged) {
            unavailableLogged = true;
            LOG.warn("SDL GPU requested but unavailable, running on OpenGL: {}", reason);
        }
        return false;
    }

    /**
     * Why the SDL backend cannot be engaged.
     *
     * <p>The checks are ordered by cost and short-circuit: everything after the request flag touches
     * process-wide state, and the device probe creates a GPU device, so it must not run when SDL GPU
     * was not asked for.</p>
     *
     * @return null when every prerequisite holds
     */
    private static String unavailableReason() {
        if (!BackendOptions.sdlGpuRequested()) return "the SDL GPU backend was not requested";
        if (!isSDLGPUAvailable()) return missingRequirement();
        if (!LwjglyLayer.present()) {
            return "the host is not running LWJGLY, so it has no window a GPU device can claim; the SDL"
                + " backend does not use the older Cleanroom generation's GL window";
        }
        if (!LwjglyWindowHost.available()) {
            return "LWJGLY is installed but its window bridge is not: " + LwjglyWindowHost.requirement();
        }
        if (!isDeviceReady()) return "no SDL GPU device could be created";
        return null;
    }

    public static boolean isActive() {
        if (!isSDLGPUAvailable()) return false;
        final RenderBackend rb = BackendManager.RENDER_BACKEND;
        return rb instanceof SDLGPURenderBackend;
    }

    /**
     * Whether the SDL backend has claimed this process's window.
     *
     * <p>Set once, at the handover, and never cleared: the window is created once per process, so
     * every engine path that must differ on SDL GPU checks this flag. It stays true through
     * shutdown, where {@link #isActive()} may already report false because the backend was torn
     * down first.</p>
     */
    public static boolean isEngaged() {
        return engaged;
    }

    /**
     * Takes the window the host supplies for the SDL GPU device.
     *
     * <p>Called from the display creation path right after the display create returns. An unusable
     * handover is fatal: there is no GL context to fall back to at this point, and the window handle
     * is the one piece of state only the host can supply. Nothing is installed for the engine's
     * display-context resolution either: LWJGLY's {@code Display} owns no LWJGL2 drawable, and its
     * single window context is what a null drawable already denotes.</p>
     *
     * @throws IllegalStateException when the host has no window to hand over, or the claim failed
     */
    public static void handOverWindow() {
        if (!isHandoverAvailable()) {
            throw new IllegalStateException(
                "The SDL GPU backend was selected but can no longer be engaged: " + unavailableReason());
        }
        final long window = LwjglyWindowHost.window();
        device().claimWindow(window);
        engaged = true;
        LOG.info("SDL GPU engaged on the LWJGLY host window 0x{}", Long.toHexString(window));
        BackendManager.RENDER_BACKEND.onPostWindowCreate(window);
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
