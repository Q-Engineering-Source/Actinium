package com.gtnewhorizons.angelica.glsm.backend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

import java.util.List;

/**
 * SDL implementations of the {@link WindowSystem} calls, for hosts whose window is an SDL window
 * (newer Cleanroom running LWJGLY).
 *
 * <p>Every {@code org.lwjgl.sdl} reference of the GL render path lives here and in
 * {@link SdlFileDropWatcher}. The class is loaded only after {@link WindowSystem}'s probe has
 * found the LWJGL 3 SDL binding; a GLFW-windowed host has no lwjgl-sdl on its classpath, and
 * nothing on that run may touch this class. The SDL3 native needs no separate setup on the runs
 * that reach this class: LWJGLY's own {@code org.lwjgl} adapters already call into the same
 * binding for display-mode and input queries.</p>
 */
final class SdlWindowing {

    private static final Logger LOGGER = LogManager.getLogger("SdlWindowing");

    private SdlWindowing() {
    }

    static void swapInterval(int interval) {
        if (!SDLVideo.SDL_GL_SetSwapInterval(interval)) {
            LOGGER.warn("SDL_GL_SetSwapInterval({}) failed: {}", interval, SDLError.SDL_GetError());
        }
    }

    static long queryRefreshPeriodNanos() {
        final int display = SDLVideo.SDL_GetPrimaryDisplay();
        if (display == 0) return 0L;
        SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(display);
        if (mode == null) {
            mode = SDLVideo.SDL_GetDesktopDisplayMode(display);
        }
        if (mode == null) return 0L;
        return RenderBackend.periodFromRational(mode.refresh_rate_numerator(), mode.refresh_rate_denominator(), mode.refresh_rate());
    }

    static long currentContext() {
        return SDLVideo.SDL_GL_GetCurrentContext();
    }

    static void startFileDrop() {
        SdlFileDropWatcher.start();
    }

    static void stopFileDrop() {
        SdlFileDropWatcher.stop();
    }

    static List<String> pollDroppedFiles() {
        return SdlFileDropWatcher.pollDroppedFiles();
    }
}
