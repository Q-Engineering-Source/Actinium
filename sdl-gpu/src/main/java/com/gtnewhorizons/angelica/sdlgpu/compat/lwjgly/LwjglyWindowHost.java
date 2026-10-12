package com.gtnewhorizons.angelica.sdlgpu.compat.lwjgly;

import com.cleanroommc.lwjgly.LWJGLY;

/**
 * The host window the SDL GPU backend works with, on a Cleanroom running LWJGLY.
 *
 * <p>This class is the only place in the SDL enable path that names a
 * {@code com.cleanroommc.lwjgly.*} type, and it is loaded only once
 * {@link com.gtnewhorizons.angelica.sdlgpu.compat.LwjglyLayer#present()} has confirmed LWJGLY is
 * installed - asked by the gate, or by the backend once it is the selected one. Nothing here is
 * loaded on an OpenGL run.</p>
 *
 * <p>On this host the window is not a mod's business at all: LWJGLY's
 * {@code org.lwjgl.opengl.Display} creates nothing and forwards every window operation to the
 * {@code com.cleanroommc.lwjgly.spi.WindowBridge} the host installed, so the backend simply claims
 * the bridge's own {@code SDL_Window} and reads its size and resize state from the same place. There
 * is no drawable object to take over either: LWJGLY's {@code Display.getDrawable()} returns
 * {@code null}, GL state tracking treats a null drawable as the display context, and that is exactly
 * what this backend's single context is.</p>
 *
 * <p>What the host still has to arrange, and what this class can only report when it is missing, is a
 * window a GPU device can use: SDL cannot create a Vulkan or Direct3D 12 swapchain for a window that
 * was created as an OpenGL window, so a host that wants the SDL GPU backend has to publish a window
 * created for the GPU backend through the bridge.</p>
 */
public final class LwjglyWindowHost {

    private LwjglyWindowHost() {
    }

    /**
     * Whether the host has installed its window bridge yet.
     *
     * <p>The host installs it once its window exists and before any mod code runs, so this is a live
     * question rather than a class-presence one, and it is asked both at backend selection and again
     * at the handover.</p>
     *
     * @return true when {@code LWJGLY.setWindowBridge} has been called
     */
    public static boolean available() {
        return LWJGLY.hasWindowBridge();
    }

    /**
     * Describes what the host must provide for {@link #available()} to become true.
     *
     * @return the host's obligation, for the warning that explains why a requested SDL run stayed on
     *         OpenGL
     */
    public static String requirement() {
        return "the host must install its LWJGLY window bridge with LWJGLY.setWindowBridge(...), and the"
            + " SDL_Window it reports must be one a GPU device can use (created for the GPU backend, not"
            + " as an SDL_WINDOW_OPENGL window)";
    }

    /**
     * Returns the {@code SDL_Window} pointer the host published for this process.
     *
     * <p>The backend claims exactly this window for its GPU device and never creates, resizes or
     * destroys a window itself.</p>
     *
     * @return the non-zero {@code SDL_Window} pointer
     * @throws IllegalStateException when the host's bridge reports no window, naming the obligation
     *                               only the host can satisfy
     */
    public static long window() {
        final long handle = LWJGLY.windowBridge().handle();
        if (handle == 0L) {
            throw new IllegalStateException(
                "The SDL GPU backend is engaged but LWJGLY's window bridge reports no SDL_Window. " + requirement());
        }
        return handle;
    }

    /**
     * Returns the window's current client-area width.
     *
     * @return the width the host window reports, as the backend needs it for splash sizing
     */
    public static int width() {
        return LWJGLY.windowBridge().width();
    }

    /**
     * Returns the window's current client-area height.
     *
     * @return the height the host window reports
     */
    public static int height() {
        return LWJGLY.windowBridge().height();
    }

    /**
     * Consumes the window's resize notification, which is what makes the backend rebuild its
     * swapchain.
     *
     * @return true once after one or more resizes, then false until the next one
     */
    public static boolean consumeResized() {
        return LWJGLY.windowBridge().consumeResized();
    }
}
