package com.gtnewhorizons.angelica.glsm.backend;

import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

import java.util.List;

/** GLFW window operations used by the existing LWJGL runtime. */
public final class GlfwWindowBackend implements WindowBackend {
    @Override
    public long currentContextHandle() {
        return GLFW.glfwGetCurrentContext();
    }

    @Override
    public boolean supportsFileDrop() {
        return true;
    }

    @Override
    public void startFileDrop() {
        GlfwFileDropWatcher.start();
    }

    @Override
    public void stopFileDrop() {
        GlfwFileDropWatcher.stop();
    }

    @Override
    public List<String> pollDroppedFiles() {
        return GlfwFileDropWatcher.pollDroppedFiles();
    }

    @Override
    public void setExclusiveFullscreen(boolean fullscreen) {
        Display.setFullscreen(fullscreen);
    }

    @Override
    public void setSwapInterval(boolean vsync) {
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
    }

    @Override
    public long refreshPeriodNanos() {
        final long window = Display.getWindow();
        if (window == 0L) return 0L;
        long monitor = GLFW.glfwGetWindowMonitor(window);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == 0L) return 0L;
        final GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        return mode == null ? 0L : RenderBackend.periodFromIntegerHz(mode.refreshRate());
    }

    @Override
    public void pumpMessages() {
        Display.processMessages();
    }
}
