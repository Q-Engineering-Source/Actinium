package com.gtnewhorizons.angelica.glsm.backend;

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
}
