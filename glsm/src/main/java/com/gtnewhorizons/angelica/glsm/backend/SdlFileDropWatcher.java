package com.gtnewhorizons.angelica.glsm.backend;

import org.lwjgl.sdl.SDL_DropEvent;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.SDL_AddEventWatch;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DROP_FILE;
import static org.lwjgl.sdl.SDLEvents.SDL_RemoveEventWatch;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowID;

/** SDL event-watch implementation of game-window file drag and drop. */
public final class SdlFileDropWatcher {
    private static final ConcurrentLinkedQueue<String> DROPPED_FILES = new ConcurrentLinkedQueue<>();

    private static SDL_EventFilter dropCallback;
    private static volatile int dropWindowId;

    private SdlFileDropWatcher() {}

    /** Starts watching file drops for the supplied SDL window. */
    public static void start(long window) {
        if (dropCallback != null) {
            return;
        }
        if (window == 0L) {
            throw new IllegalStateException("Cannot watch file drops without an SDL window");
        }

        int windowId = SDL_GetWindowID(window);
        if (windowId == 0) {
            throw new IllegalStateException("SDL_GetWindowID failed: " + SDL_GetError());
        }

        SDL_EventFilter callback = SDL_EventFilter.create((userdata, event) -> onEvent(event));
        dropWindowId = windowId;
        if (!SDL_AddEventWatch(callback, 0L)) {
            dropWindowId = 0;
            callback.free();
            throw new IllegalStateException("SDL_AddEventWatch failed: " + SDL_GetError());
        }
        dropCallback = callback;
    }

    /** Stops watching, releases the native callback and discards queued paths. */
    public static void stop() {
        if (dropCallback == null) {
            return;
        }

        SDL_RemoveEventWatch(dropCallback, 0L);
        dropCallback.free();
        dropCallback = null;
        dropWindowId = 0;
        DROPPED_FILES.clear();
    }

    /** Returns and removes paths in drop order. */
    public static List<String> pollDroppedFiles() {
        if (DROPPED_FILES.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> paths = new ArrayList<>();
        String path;
        while ((path = DROPPED_FILES.poll()) != null) {
            paths.add(path);
        }
        return paths;
    }

    static boolean onEvent(long eventAddress) {
        SDL_Event event = SDL_Event.create(eventAddress);
        if (event.type() != SDL_EVENT_DROP_FILE) {
            return true;
        }

        SDL_DropEvent drop = event.drop();
        if (drop.windowID() == dropWindowId && dropWindowId != 0) {
            DROPPED_FILES.add(drop.dataString());
        }
        return true;
    }
}
