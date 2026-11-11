package com.gtnewhorizons.angelica.glsm.backend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.SDL_PumpEvents;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_SetSwapInterval;
import static org.lwjgl.sdl.SDLVideo.SDL_GetClosestFullscreenDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreen;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreenMode;
import static org.lwjgl.sdl.SDLVideo.SDL_SyncWindow;

/** SDL window operations used when the SDL3 loader owns the game window. */
public final class SdlWindowBackend implements WindowBackend {
    private static final Logger LOGGER = LogManager.getLogger("SdlWindowBackend");

    @Override
    public long currentContextHandle() {
        return SDL_GL_GetCurrentContext();
    }

    @Override
    public boolean supportsFileDrop() {
        return true;
    }

    @Override
    public void startFileDrop() {
        SdlFileDropWatcher.start(Display.getWindow());
    }

    @Override
    public void stopFileDrop() {
        SdlFileDropWatcher.stop();
    }

    @Override
    public List<String> pollDroppedFiles() {
        return SdlFileDropWatcher.pollDroppedFiles();
    }

    @Override
    public void setExclusiveFullscreen(boolean fullscreen) {
        long window = Display.getWindow();
        if (window == 0L) {
            throw new IllegalStateException("Cannot change fullscreen state without an SDL window");
        }
        if (BackendManager.RENDER_BACKEND.isSDLGPU()) {
            BackendManager.RENDER_BACKEND.onPreSwapchainInvalidatingChange(null);
        }

        if (fullscreen) {
            enterExclusiveFullscreen(window);
        } else {
            requireSdl(SDL_SetWindowFullscreen(window, false), "SDL_SetWindowFullscreen");
            requireSdl(SDL_SyncWindow(window), "SDL_SyncWindow");
            Display.setBorderless(false);
        }
    }

    private static void enterExclusiveFullscreen(long window) {
        int display = SDL_GetDisplayForWindow(window);
        if (display == 0) {
            throw new IllegalStateException("SDL_GetDisplayForWindow failed: " + SDL_GetError());
        }

        DisplayMode desktopMode = Display.getDesktopDisplayMode();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_DisplayMode closestMode = SDL_DisplayMode.malloc(stack);
            requireSdl(
                SDL_GetClosestFullscreenDisplayMode(
                    display,
                    desktopMode.getWidth(),
                    desktopMode.getHeight(),
                    desktopMode.getFrequency(),
                    true,
                    closestMode
                ),
                "SDL_GetClosestFullscreenDisplayMode"
            );
            requireSdl(SDL_SetWindowFullscreenMode(window, closestMode), "SDL_SetWindowFullscreenMode");
        }
        requireSdl(SDL_SetWindowFullscreen(window, true), "SDL_SetWindowFullscreen");
        requireSdl(SDL_SyncWindow(window), "SDL_SyncWindow");
    }

    private static void requireSdl(boolean success, String operation) {
        if (!success) {
            throw new IllegalStateException(operation + " failed: " + SDL_GetError());
        }
    }

    @Override
    public void setSwapInterval(boolean vsync) {
        if (!SDL_GL_SetSwapInterval(vsync ? 1 : 0)) {
            LOGGER.warn("SDL_GL_SetSwapInterval({}) failed: {}", vsync ? 1 : 0, SDL_GetError());
        }
    }

    @Override
    public long refreshPeriodNanos() {
        final long window = Display.getWindow();
        final int display = window == 0L ? SDL_GetPrimaryDisplay() : SDL_GetDisplayForWindow(window);
        if (display == 0) return 0L;
        final SDL_DisplayMode mode = SDL_GetCurrentDisplayMode(display);
        if (mode == null) return 0L;
        return RenderBackend.periodFromRational(mode.refresh_rate_numerator(), mode.refresh_rate_denominator(), mode.refresh_rate());
    }

    @Override
    public void pumpMessages() {
        SDL_PumpEvents();
    }
}
