package com.gtnewhorizons.angelica.glsm.backend;

import java.util.List;

/**
 * Window-system operations required by the render backend, the present path and the shader-pack file drop UI.
 * Implementations keep the active window toolkit behind one API so the frame pacer never branches on the loader.
 */
public interface WindowBackend {
    /** Returns the native OpenGL context current on the calling thread, or zero when none is current. */
    long currentContextHandle();

    /** Returns whether this window system can deliver file drop events. */
    boolean supportsFileDrop();

    /** Starts collecting file paths dropped on the game window. */
    void startFileDrop();

    /** Stops collecting file drops and releases the associated native callback. */
    void stopFileDrop();

    /** Returns and removes the dropped paths received since the previous poll. */
    List<String> pollDroppedFiles();

    /** Changes exclusive fullscreen state. Must be called on the Minecraft client/main thread. */
    void setExclusiveFullscreen(boolean fullscreen);

    /** Returns whether this window system supports a native exclusive fullscreen transition. */
    default boolean supportsExclusiveFullscreen() {
        return true;
    }

    /**
     * Requests the given swap interval from the window system.
     *
     * <p>Neither GLFW nor SDL reports a failure that can be attributed to the driver policy, so the frame pacer treats
     * the request as advisory and decides from gate samples whether the driver actually blocks on presentation.
     */
    void setSwapInterval(boolean vsync);

    /**
     * Returns the refresh period in nanoseconds of the display presenting the game window, or zero when the window
     * system cannot report it. The value is the raw period; plausibility filtering happens in {@link RenderBackend}.
     */
    long refreshPeriodNanos();

    /** Processes pending window-system events without presenting a frame. */
    void pumpMessages();
}
