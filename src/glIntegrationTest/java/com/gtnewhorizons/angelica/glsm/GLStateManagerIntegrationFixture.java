package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;

public abstract class GLStateManagerIntegrationFixture {
    protected static final int WINDOW_WIDTH = 64;
    protected static final int WINDOW_HEIGHT = 64;

    protected static long window;
    protected static long context;

    @BeforeAll
    static void createGlContext() {
        assertTrue(SDL_Init(SDL_INIT_VIDEO), () -> sdlError("SDL_Init"));
        window = SDL_CreateWindow(
            "Actinium GLSM integration", WINDOW_WIDTH, WINDOW_HEIGHT, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
        assertNotEquals(MemoryUtil.NULL, window, () -> sdlError("SDL_CreateWindow"));
        context = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, context, () -> sdlError("SDL_GL_CreateContext"));
        assertTrue(SDL_GL_MakeCurrent(window, context), () -> sdlError("SDL_GL_MakeCurrent"));
        GL.createCapabilities();
    }

    @AfterAll
    static void destroyGlContext() {
        try {
            if (context != MemoryUtil.NULL) {
                assertTrue(SDL_GL_DestroyContext(context), () -> sdlError("SDL_GL_DestroyContext"));
            }
        } finally {
            try {
                if (window != MemoryUtil.NULL) {
                    SDL_DestroyWindow(window);
                }
            } finally {
                SDL_Quit();
            }
        }
    }

    private static String sdlError(String operation) {
        return operation + " failed: " + SDL_GetError();
    }
}
