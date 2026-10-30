package com.gtnewhorizons.angelica.glsm.backend;

import java.util.List;

/**
 * Window-system operations required by the render backend and shader-pack file drop UI.
 * Implementations keep the active window toolkit behind one API.
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
}
