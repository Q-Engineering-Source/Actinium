package com.gtnewhorizons.angelica.glsm.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDL_DropEvent;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_FILE;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_TEXT;
import static org.lwjgl.sdl.SDLEvents.SDL_FlushEvent;
import static org.lwjgl.sdl.SDLEvents.SDL_PushEvent;
import static org.lwjgl.sdl.SDLStdinc.SDL_free;
import static org.lwjgl.sdl.SDLStdinc.SDL_malloc;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowID;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;

class SdlWindowBackendIntegrationTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;

    private boolean sdlInitialized;
    private long window;
    private long context;

    @BeforeEach
    void createHiddenWindow() {
        sdlInitialized = SDL_Init(SDL_INIT_VIDEO);
        assertTrue(sdlInitialized, () -> sdlError("SDL_Init"));
        window = SDL_CreateWindow("Actinium SDL integration", WIDTH, HEIGHT, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
        assertNotEquals(MemoryUtil.NULL, window, () -> sdlError("SDL_CreateWindow"));
    }

    @AfterEach
    void destroyWindow() {
        try {
            SdlFileDropWatcher.stop();
            if (context != MemoryUtil.NULL) {
                assertTrue(SDL_GL_DestroyContext(context), () -> sdlError("SDL_GL_DestroyContext"));
            }
        } finally {
            try {
                if (window != MemoryUtil.NULL) {
                    SDL_DestroyWindow(window);
                }
            } finally {
                if (sdlInitialized) {
                    SDL_Quit();
                }
                WindowBackends.useGlfw();
            }
        }
    }

    @Test
    void returnsTheCurrentSdlContextThroughTheSelectedBackend() {
        context = SDL_GL_CreateContext(window);
        assertNotEquals(MemoryUtil.NULL, context, () -> sdlError("SDL_GL_CreateContext"));
        assertTrue(SDL_GL_MakeCurrent(window, context), () -> sdlError("SDL_GL_MakeCurrent"));

        WindowBackends.useSdl();
        WindowBackend backend = WindowBackends.get();
        long handle = backend.currentContextHandle();

        assertNotEquals(MemoryUtil.NULL, handle);
        assertEquals(SDL_GL_GetCurrentContext(), handle);
        WindowBackends.useSdl();
        assertSame(backend, WindowBackends.get());
    }

    @Test
    void watcherFiltersEventsQueuesPathsInOrderAndStopsCleanly() {
        int windowId = SDL_GetWindowID(window);
        assertNotEquals(0, windowId, () -> sdlError("SDL_GetWindowID"));
        SdlFileDropWatcher.start(window);
        SdlFileDropWatcher.start(window);

        dispatchDropEvent(SDL_EVENT_DROP_TEXT, windowId, "wrong-type.zip");
        dispatchDropEvent(SDL_EVENT_DROP_FILE, windowId + 1, "wrong-window.zip");
        assertTrue(SdlFileDropWatcher.pollDroppedFiles().isEmpty());

        dispatchDropEvent(SDL_EVENT_DROP_FILE, windowId, "first.zip");
        dispatchDropEvent(SDL_EVENT_DROP_FILE, windowId, "second.zip");
        assertEquals(List.of("first.zip", "second.zip"), SdlFileDropWatcher.pollDroppedFiles());
        assertTrue(SdlFileDropWatcher.pollDroppedFiles().isEmpty());

        SdlFileDropWatcher.stop();
        SdlFileDropWatcher.stop();
        dispatchDropEvent(SDL_EVENT_DROP_FILE, windowId, "after-stop.zip");
        assertTrue(SdlFileDropWatcher.pollDroppedFiles().isEmpty());

        SdlFileDropWatcher.start(window);
        dispatchDropEvent(SDL_EVENT_DROP_FILE, windowId, "after-restart.zip");
        assertEquals(List.of("after-restart.zip"), SdlFileDropWatcher.pollDroppedFiles());
    }

    private static void dispatchDropEvent(int type, int windowId, String path) {
        byte[] encodedPath = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = SDL_malloc(encodedPath.length + 1L);
        assertNotNull(data, () -> sdlError("SDL_malloc"));
        data.put(encodedPath).put((byte) 0).flip();

        SDL_Event event = SDL_Event.calloc();
        try {
            event.type(type);
            SDL_DropEvent drop = event.drop();
            drop.windowID(windowId);
            drop.data(data);
            assertTrue(SDL_PushEvent(event), () -> sdlError("SDL_PushEvent"));
            SDL_FlushEvent(type);
        } finally {
            event.free();
            SDL_free(data);
        }
    }

    private static String sdlError(String operation) {
        return operation + " failed: " + SDL_GetError();
    }
}
