package com.dhj.actinium.startup;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;

/** Pairs SDL command recording with every Minecraft display update, including loading screens. */
public final class SdlFrameLifecycle {
    private SdlFrameLifecycle() {}

    public static void beginFrame() {
        if (!SdlGpuBootstrap.isActive()) return;
        BackendManager.RENDER_BACKEND.onFrameBegin();
    }

    public static void endFrame() {
        if (!SdlGpuBootstrap.isActive()) return;
        beginFrame();
        BackendManager.RENDER_BACKEND.onFrameEnd();
    }
}
