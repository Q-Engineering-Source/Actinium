package com.gtnewhorizons.angelica.proxy;

import com.dhj.actinium.config.AnimationMode;
import com.dhj.actinium.config.ManagedEnum;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;

import java.io.IOException;

public final class ClientProxy {
    public static final ManagedEnum<AnimationMode> animationsMode = new ManagedEnum<>(AnimationMode.VISIBLE_ONLY);

    private ClientProxy() {
    }

    public static SodiumGameOptions options() {
        return ActiniumRuntime.options();
    }

    /**
     * Applies a vsync state in the two-valued form vanilla's own option speaks.
     *
     * <p>Vanilla's option is a toggle, so it cannot say which tear-free mode the player means; the
     * mode the three-state option last preferred is used, and the resolution is written back so the
     * two screens keep showing the same thing. Vanilla's own toggle does not save the file it
     * changes, which is why this persists explicitly.</p>
     */
    public static void toggleVSync(boolean enabled) {
        final SodiumGameOptions opts = options();
        opts.advanced.vsyncMode = enabled ? GLStateManager.preferredTearFreeMode() : VSyncMode.OFF;
        persistVSyncMode(opts);
    }

    private static void persistVSyncMode(SodiumGameOptions opts) {
        try {
            opts.writeChanges();
        } catch (IOException e) {
            ActiniumRuntime.logger().warn("Could not persist the vsync mode", e);
        }
        GLStateManager.setVSyncMode(opts.advanced.vsyncMode);
    }
}
