package com.gtnewhorizons.angelica.glsm.backend;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.util.List;

/**
 * GLFW implementations of the {@link WindowSystem} calls, for hosts whose window is a GLFW window
 * (older Cleanroom: lwjglx/lwjglxx over the LWJGL 3 stack that generation ships).
 *
 * <p>Every {@code org.lwjgl.glfw} reference of the GL render path lives here and in
 * {@link GlfwFileDropWatcher}. The class is loaded only after {@link WindowSystem}'s probe has
 * found {@code org.lwjgl.glfw.GLFW}; an LWJGLY host has no {@code org.lwjgl.glfw} classes, and
 * nothing on that run may touch this class.</p>
 */
final class GlfwWindowing {

    private GlfwWindowing() {
    }

    static void swapInterval(int interval) {
        GLFW.glfwSwapInterval(interval);
    }

    static long queryRefreshPeriodNanos() {
        final long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == 0) return 0L;
        final GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode == null) return 0L;
        return RenderBackend.periodFromIntegerHz(mode.refreshRate());
    }

    static long currentContext() {
        return GLFW.glfwGetCurrentContext();
    }

    static void startFileDrop() {
        GlfwFileDropWatcher.start();
    }

    static void stopFileDrop() {
        GlfwFileDropWatcher.stop();
    }

    static List<String> pollDroppedFiles() {
        return GlfwFileDropWatcher.pollDroppedFiles();
    }
}
