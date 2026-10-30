package com.gtnewhorizons.angelica.glsm.backend;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GetClosestFullscreenDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreen;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreenMode;
import static org.lwjgl.sdl.SDLVideo.SDL_SyncWindow;

/** SDL window operations used when the SDL3 loader owns the game window. */
public final class SdlWindowBackend implements WindowBackend {
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
}
