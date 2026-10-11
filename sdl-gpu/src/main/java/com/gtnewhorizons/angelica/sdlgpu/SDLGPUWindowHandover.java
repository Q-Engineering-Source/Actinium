package com.gtnewhorizons.angelica.sdlgpu;

import net.minecraft.launchwrapper.Launch;

/**
 * Reader side of the window handover contract between Actinium and the Vintage-SDL-Bridge coremod.
 *
 * <p>A GPU device cannot claim a window that carries a GL context
 * ({@code VK_ERROR_NATIVE_WINDOW_IN_USE_KHR}), and Cleanroom's lwjglx shim always creates a GLFW
 * window with one. The window therefore comes from the bridge: it replaces the window creation
 * inside {@code org.lwjgl.opengl.Display.create} with a native SDL window and publishes the
 * {@code SDL_Window} pointer on {@link Launch#blackboard} under {@link #WINDOW_KEY} before that
 * call returns. Actinium reads the handle here, claims it for its own GPU device and never
 * touches the window again - creating, resizing and destroying it stay the bridge's business.</p>
 *
 * <p>The contract is intentionally narrow (one key, one type) so both sides can be implemented and
 * tested independently. A missing or malformed entry is a hard failure: silently rendering into
 * no window is worse than refusing to start.</p>
 */
public final class SDLGPUWindowHandover {

    /**
     * Blackboard key holding the {@code SDL_Window} pointer created by the bridge, as a {@link Long}.
     */
    public static final String WINDOW_KEY = "actinium.sdlgpu.sdlWindow";

    /**
     * Coremod plugin class of the bridge. Its presence on the classpath means a window supplier
     * exists; absence means the SDL backend cannot be engaged and OpenGL must stay selected.
     */
    public static final String BRIDGE_CLASS = "com.dhj.vintagesdlbridge.VintageSdlBridgeLoadingPlugin";

    private SDLGPUWindowHandover() {}

    /**
     * Returns the SDL window handle the bridge published for this process.
     *
     * @return the non-zero {@code SDL_Window} pointer
     * @throws IllegalStateException when the handover key is missing, null, or not a {@link Long}
     */
    public static long readWindow() {
        final Object value = Launch.blackboard.get(WINDOW_KEY);
        if (value == null) {
            throw new IllegalStateException(
                "The SDL GPU backend is engaged but Launch.blackboard['" + WINDOW_KEY + "'] is unset. "
                    + "The " + BRIDGE_CLASS + " coremod must create the SDL window and publish its SDL_Window "
                    + "pointer under that key before org.lwjgl.opengl.Display.create returns");
        }
        if (!(value instanceof Long)) {
            throw new IllegalStateException(
                "Launch.blackboard['" + WINDOW_KEY + "'] must be a Long SDL_Window pointer but is a "
                    + value.getClass().getName());
        }
        final long handle = (Long) value;
        if (handle == 0L) {
            throw new IllegalStateException(
                "Launch.blackboard['" + WINDOW_KEY + "'] is a null SDL_Window pointer; the bridge did not "
                    + "create a window");
        }
        return handle;
    }

    /**
     * Probes for the bridge coremod without initializing it: the plugin's static initializer chain
     * reaches into Forge's loading infrastructure, which must not run before FML is up.
     *
     * @param loader class loader to probe; the bridge and this backend share the launch class loader
     * @return true when the bridge plugin class is visible from {@code loader}
     */
    public static boolean isBridgePresent(ClassLoader loader) {
        try {
            Class.forName(BRIDGE_CLASS, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
