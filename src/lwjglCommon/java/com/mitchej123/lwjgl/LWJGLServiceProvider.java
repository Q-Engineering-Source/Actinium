package com.mitchej123.lwjgl;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;

public final class LWJGLServiceProvider {
    public static final LWJGLService LWJGL = createInstance();
    public static final int POINTER_SIZE = LWJGL.getPointerSize();
    public static final long NULL = 0L;

    private LWJGLServiceProvider() {
    }

    static LWJGLService constructInstance(String className) {
        try {
            var clz = Class.forName(className);
            var method = clz.getDeclaredMethod("create");
            return (LWJGLService) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static LWJGLService createInstance() {
        // The service is picked by the render backend the process actually runs on, not by a flag:
        // on the SDL GPU backend there is no GL context, so the GL-backed service would hand
        // Celeritas/Embeddium the sentinel function pointers of the fabricated capabilities.
        // Reading BackendManager here forces backend selection (it is a static field) before the
        // service is bound, which is what makes the answer final for the rest of the process.
        // The reflective lookup keeps this class - compiled into the glsm source set - free of a
        // compile-time dependency on the sdl-gpu module.
        if (BackendManager.RENDER_BACKEND.isSDLGPU()) {
            return constructInstance("com.gtnewhorizons.angelica.sdlgpu.SDLGPULWJGLService");
        }
        return constructInstance("com.mitchej123.lwjgl.lwjgl3.LWJGL3Service");
    }
}

