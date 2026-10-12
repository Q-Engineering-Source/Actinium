package com.gtnewhorizons.angelica.sdlgpu.compat;

import com.gtnewhorizons.angelica.sdlgpu.compat.lwjgly.LwjglyWindowHost;

/**
 * Whether this process runs on a Cleanroom that installs LWJGLY.
 *
 * <p>Actinium ships one jar for two host generations, and the SDL GPU backend exists on only one of
 * them. An older Cleanroom builds its window and its GL context with {@code com.cleanroommc:lwjglx}
 * plus the lwjgl3ify-style shims in {@code com.cleanroommc:lwjglxx}, and a window created that way
 * always carries a GL context, which SDL cannot turn into a Vulkan or Direct3D 12 swapchain - so
 * that host has no SDL route at all. A newer Cleanroom runs LWJGLY
 * ({@code com.cleanroommc:lwjgly}), where {@code org.lwjgl.opengl.Display} creates nothing itself and
 * forwards every window, context and input operation to the host's
 * {@code com.cleanroommc.lwjgly.spi.WindowBridge}, which is the window the backend claims.</p>
 *
 * <p>This class is the whole of the SDL path's knowledge about which generation it is on, and it
 * holds no LWJGLY types itself: it only looks the layer's entry point up by name, so an old-Cleanroom
 * run can answer "not available" without ever loading a {@code com.cleanroommc.lwjgly.*} class. The
 * answer is computed once; the probe does not initialize the class it finds, both because the layer's
 * own static initialisers are the host's business and because probing must have no side effects on a
 * host that is not running LWJGLY.</p>
 */
public final class LwjglyLayer {

    /** Entry point of LWJGLY; its presence identifies the newer Cleanroom. */
    private static final String LWJGLY = "com.cleanroommc.lwjgly.LWJGLY";

    private static final boolean PRESENT = present(LWJGLY, LwjglyLayer.class.getClassLoader());

    private LwjglyLayer() {
    }

    /**
     * Whether LWJGLY is installed, which is the first prerequisite of the SDL GPU backend: without it
     * the backend has no window it could claim and no host to ask for one.
     *
     * @return true when LWJGLY is visible from this mod's class loader
     */
    public static boolean present() {
        return PRESENT;
    }

    /**
     * Probes for a class without initializing it.
     *
     * @param className binary name of the class to look for
     * @param loader loader to probe from; the layer and this mod share the launch class loader
     * @return true when the class is visible from {@code loader}
     */
    static boolean present(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
