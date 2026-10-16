package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

import java.util.Collections;
import java.util.List;

/**
 * The window-system calls the GL render path needs: vsync, display refresh rate, current-context
 * identity and window file drops.
 *
 * <p>One Actinium jar runs on two Cleanroom generations whose windows belong to different window
 * systems. An older Cleanroom (lwjglx/lwjglxx) builds its window with GLFW, so
 * {@code org.lwjgl.glfw.**} resolves there; a newer Cleanroom running LWJGLY builds its window
 * with SDL and has no {@code org.lwjgl.glfw} classes at all, while {@code org.lwjgl.sdl.**} is
 * guaranteed present because LWJGLY's own {@code org.lwjgl} adapters call into the LWJGL 3 SDL
 * binding. A single class-presence probe therefore decides the generation; it is computed once
 * and never re-evaluated.</p>
 *
 * <p>This class holds no GLFW or SDL types: it looks the two window-system entry points up by
 * name without initializing them, so a run can answer "not this generation" without loading a
 * class the host does not have. Every GLFW reference lives in {@link GlfwWindowing} and
 * {@link GlfwFileDropWatcher}, every SDL reference in {@link SdlWindowing} and
 * {@link SdlFileDropWatcher}, and each pair is loaded only after the probe has vouched for its
 * window system.</p>
 *
 * <p>A host with neither window system cannot happen on a supported Cleanroom; if it ever does,
 * each operation degrades with a one-time WARN instead of failing silently.</p>
 */
public final class WindowSystem {

    /** GLFW's entry point; present exactly on hosts whose window is a GLFW window. */
    private static final String GLFW = "org.lwjgl.glfw.GLFW";
    /** The LWJGL 3 SDL binding LWJGLY runs on; present exactly on hosts whose window is an SDL window. */
    private static final String SDL = "org.lwjgl.sdl.SDLVideo";

    private static final boolean GLFW_PRESENT = present(GLFW);
    private static final boolean SDL_PRESENT = !GLFW_PRESENT && present(SDL);

    private WindowSystem() {
    }

    /**
     * Applies the swap interval to the current context ({@code glfwSwapInterval} /
     * {@code SDL_GL_SetSwapInterval}).
     */
    public static void swapInterval(int interval) {
        if (GLFW_PRESENT) {
            GlfwWindowing.swapInterval(interval);
            return;
        }
        if (SDL_PRESENT) {
            SdlWindowing.swapInterval(interval);
            return;
        }
        unsupported("swapInterval");
    }

    /**
     * Raw refresh-period query behind {@link RenderBackend#queryRefreshPeriodNanos()}: the period
     * of the primary display's current video mode in nanoseconds, or 0 when unknown.
     */
    public static long queryRefreshPeriodNanos() {
        if (GLFW_PRESENT) {
            return GlfwWindowing.queryRefreshPeriodNanos();
        }
        if (SDL_PRESENT) {
            return SdlWindowing.queryRefreshPeriodNanos();
        }
        unsupported("queryRefreshPeriodNanos");
        return 0L;
    }

    /**
     * Identity of the current GL context ({@code glfwGetCurrentContext} /
     * {@code SDL_GL_GetCurrentContext}), or 0 when no context is current. Callers only ever
     * compare the value for equality, so the two generations may return unrelated handle kinds.
     */
    public static long currentContext() {
        if (GLFW_PRESENT) {
            return GlfwWindowing.currentContext();
        }
        if (SDL_PRESENT) {
            return SdlWindowing.currentContext();
        }
        unsupported("currentContext");
        return 0L;
    }

    /**
     * The framebuffer size in pixels: lwjglx's exact framebuffer query on the GLFW generation, the
     * logical window size on LWJGLY (the two differ only on HiDPI hosts).
     */
    public static int framebufferWidth() {
        if (GLFW_PRESENT) {
            return GlfwWindowing.framebufferWidth();
        }
        if (SDL_PRESENT) {
            return SdlWindowing.framebufferWidth();
        }
        unsupported("framebufferWidth");
        return 0;
    }

    /** See {@link #framebufferWidth()}. */
    public static int framebufferHeight() {
        if (GLFW_PRESENT) {
            return GlfwWindowing.framebufferHeight();
        }
        if (SDL_PRESENT) {
            return SdlWindowing.framebufferHeight();
        }
        unsupported("framebufferHeight");
        return 0;
    }

    /** Whether files dropped onto the window can be watched on this host. */
    public static boolean supportsFileDrop() {
        return GLFW_PRESENT || SDL_PRESENT;
    }

    /** Starts queueing dropped file paths. Must be idempotent. */
    public static void startFileDrop() {
        if (GLFW_PRESENT) {
            GlfwWindowing.startFileDrop();
            return;
        }
        if (SDL_PRESENT) {
            SdlWindowing.startFileDrop();
            return;
        }
        unsupported("startFileDrop");
    }

    /** Stops queueing dropped file paths and releases the native callback. Must be idempotent. */
    public static void stopFileDrop() {
        if (GLFW_PRESENT) {
            GlfwWindowing.stopFileDrop();
            return;
        }
        if (SDL_PRESENT) {
            SdlWindowing.stopFileDrop();
            return;
        }
        // Nothing was started on a host with no window system, so there is nothing to release.
    }

    /** Returns the file paths dropped on the window since the last call. */
    public static List<String> pollDroppedFiles() {
        if (GLFW_PRESENT) {
            return GlfwWindowing.pollDroppedFiles();
        }
        if (SDL_PRESENT) {
            return SdlWindowing.pollDroppedFiles();
        }
        return Collections.emptyList();
    }

    private static void unsupported(String operation) {
        GLStateManager.warnOnce("windowSystem." + operation,
            "No GLFW or SDL window system is visible on the classpath; " + operation + " is unsupported on this host");
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, WindowSystem.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
