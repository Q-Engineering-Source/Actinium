package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.sdlgpu.compat.Lwjgl3GLCapabilitiesShim;
import org.lwjgl.MainThread;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayEvents;
import org.lwjgl.opengl.GLCapabilitiesOverride;

import java.util.concurrent.Executor;
import java.util.Set;

/** Supplies Outlands window operations without depending on the mod entry point. */
public final class SDLHostHooks {
    private SDLHostHooks() {}

    public static long window() { return Display.getWindow(); }
    public static int width() { return Display.getWidth(); }
    public static int height() { return Display.getHeight(); }
    public static boolean wasResized() { return Display.wasResized(); }
    public static Executor windowExecutor() {
        return command -> {
            if (MainThread.isMainThread()) command.run();
            else MainThread.post(command);
        };
    }
    public static Executor mainExecutor() { return windowExecutor(); }
    public static boolean isWindowThread() { return MainThread.isMainThread(); }
    public static boolean isMainThread() { return isWindowThread(); }

    public static void pumpEvents() {
        if (!isWindowThread()) throw new IllegalStateException("SDL events must be pumped on the window thread");
        Display.processMessages();
    }

    public static void pollInput() {
        Mouse.poll();
        Keyboard.poll();
    }

    public static void beforeSwapchainChange(Runnable callback) {
        DisplayEvents.addPreSwapchainInvalidatingChangeListener(change -> callback.run());
    }

    public static void installCapabilities(Set<String> advertised) {
        GLCapabilitiesOverride.set(advertised);
        Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised);
    }

    public static void clearCapabilities() { Lwjgl3GLCapabilitiesShim.clearOnCurrentThread(); }
}
