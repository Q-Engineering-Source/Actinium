package com.gtnewhorizons.angelica.glsm.backend;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SDL implementation of window-level file drag & drop watching (issue #122), the counterpart of
 * {@link GlfwFileDropWatcher} for hosts whose window is an SDL window (LWJGLY).
 *
 * <p>SDL has no per-window drop callback; drop notifications arrive as
 * {@code SDL_EVENT_DROP_FILE} events on the global event queue. The watcher is an event
 * <i>watch</i>, not a consumer: the host's own pump ({@code WindowBridge.pump()} behind
 * {@code Display.processMessages()}) keeps dispatching the queue, and the watch only observes
 * events as they are pushed, returning true so they stay queued for the host. Loaded only after
 * {@link WindowSystem}'s probe has found the SDL binding.</p>
 *
 * <p>Lifecycle: start/stop around screen visibility (must be idempotent), poll each frame on the
 * main thread. The watch callback runs on the pumping thread; the queue keeps consumption safe
 * even if an event is ever pushed off the consuming thread.</p>
 */
final class SdlFileDropWatcher {

    private static SDL_EventFilter dropWatch;
    private static final ConcurrentLinkedQueue<String> DROPPED_FILES = new ConcurrentLinkedQueue<>();

    private SdlFileDropWatcher() {
    }

    /** Starts queueing paths of files dropped onto the host's SDL window. */
    static void start() {
        if (dropWatch != null) {
            return;
        }

        dropWatch = SDL_EventFilter.create((userdata, event) -> {
            if (SDL_Event.ntype(event) != SDLEvents.SDL_EVENT_DROP_FILE) {
                return true;
            }
            final String path = SDL_Event.create(event).drop().dataString();
            if (path != null && !path.isEmpty()) {
                DROPPED_FILES.add(path);
            }
            return true;
        });
        if (!SDLEvents.SDL_AddEventWatch(dropWatch, 0L)) {
            dropWatch.free();
            dropWatch = null;
            throw new IllegalStateException("Cannot watch file drops: SDL_AddEventWatch failed");
        }
    }

    /** Stops queueing, removes the event watch and releases its native callback. */
    static void stop() {
        if (dropWatch == null) {
            return;
        }

        SDLEvents.SDL_RemoveEventWatch(dropWatch, 0L);
        dropWatch.free();
        dropWatch = null;
        DROPPED_FILES.clear();
    }

    /** Returns the file paths dropped since the last call. */
    static List<String> pollDroppedFiles() {
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
