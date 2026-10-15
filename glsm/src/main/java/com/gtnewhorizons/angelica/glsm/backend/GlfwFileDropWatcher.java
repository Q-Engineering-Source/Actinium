package com.gtnewhorizons.angelica.glsm.backend;

import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GLFW implementation of window-level file drag & drop watching (issue #122).
 *
 * Motivation: the shader pack selection screen lets users drop pack or settings files
 * onto the window instead of manually copying them into the shaderpacks directory.
 * The window event plumbing is kept out of {@link Lwjgl3GLRenderBackend} so the GL
 * backend classes stay pure call forwarders; the SDL-windowed generation has its own
 * {@link SdlFileDropWatcher} behind the same {@link WindowSystem} dispatch.
 *
 * Lifecycle: start/stop around screen visibility (must be idempotent), poll each frame
 * on the main thread. GLFW fires the drop callback from glfwPollEvents (main thread);
 * the queue keeps consumption safe even if polling ever moves off the consuming thread.
 */
public final class GlfwFileDropWatcher {
    private static GLFWDropCallback dropCallback;
    // Preserve whatever callback owned the window before us (e.g. from the loader's Display shim).
    private static GLFWDropCallback previousDropCallback;
    private static long dropWindow;
    private static final ConcurrentLinkedQueue<String> DROPPED_FILES = new ConcurrentLinkedQueue<>();

    private GlfwFileDropWatcher() {}

    /** Starts queueing paths of files dropped onto the current GLFW window. */
    public static void start() {
        if (dropCallback != null) {
            return;
        }

        final long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            throw new IllegalStateException("Cannot watch file drops without a current GLFW window");
        }

        dropWindow = window;
        dropCallback = GLFWDropCallback.create((win, count, names) -> {
            for (int i = 0; i < count; i++) {
                final String path = GLFWDropCallback.getName(names, i);
                if (path != null && !path.isEmpty()) {
                    DROPPED_FILES.add(path);
                }
            }
        });
        previousDropCallback = GLFW.glfwSetDropCallback(window, dropCallback);
    }

    /** Stops queueing, restores the previous drop callback and releases our native callback. */
    public static void stop() {
        if (dropCallback == null) {
            return;
        }

        if (dropWindow != 0L) {
            // Restore the previous owner's callback instead of leaving the window without one.
            GLFW.glfwSetDropCallback(dropWindow, previousDropCallback);
            previousDropCallback = null;
        }
        dropWindow = 0L;
        dropCallback.free();
        dropCallback = null;
        DROPPED_FILES.clear();
    }

    /** Returns the file paths dropped since the last call. */
    public static List<String> pollDroppedFiles() {
        if (DROPPED_FILES.isEmpty()) {
            return Collections.emptyList();
        }

        final List<String> out = new ArrayList<>();
        String path;
        while ((path = DROPPED_FILES.poll()) != null) {
            out.add(path);
        }
        return out;
    }
}
