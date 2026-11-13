package com.gtnewhorizons.angelica.sdlgpu.compat;

import org.lwjgl.opengl.ActiniumCapabilities;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Set;

/** Builds capability tables through LWJGL's package constructor without native GL calls. */
public final class Lwjgl3GLCapabilitiesShim {
    private Lwjgl3GLCapabilitiesShim() {}

    public static void installOnCurrentThread(Set<String> advertised) {
        installFunctionProvider();
        GL.setCapabilities(create(advertised));
    }

    public static void clearOnCurrentThread() { GL.setCapabilities(null); }

    public static synchronized void installFunctionProvider() {
        if (GL.getFunctionProvider() == null) GL.create(name -> 0L);
    }

    public static GLCapabilities create(Set<String> advertised) {
        return ActiniumCapabilities.create(advertised);
    }
}
