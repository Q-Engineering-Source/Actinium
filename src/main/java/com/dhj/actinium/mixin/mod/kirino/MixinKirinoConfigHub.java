package com.dhj.actinium.mixin.mod.kirino;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Headless co-existence bridge for Kirino Engine's {@code KirinoConfigHub}.
 *
 * <p>In Graphics mode Kirino replaces {@code EntityRenderer#renderWorld} entirely, starving
 * every Actinium hook anchored to vanilla {@code renderWorldPass}; its
 * {@code enableRenderDelegate} toggle is re-forced to {@code true} in
 * {@code KirinoCommonCore#onKirinoOneTimeConfig} and has no user-facing writer, so this
 * mixin answers {@code false} at every read point and pins Kirino to Headless mode: the
 * engine and its ECS/analysis runtime still initialise ({@code isEnable()} untouched, no GL
 * resources allocated, vanilla {@code renderWorld} keeps running) while Actinium stays the
 * single owner of the rendering pipeline. Injected via a late/conditional mixin config
 * gated on the {@code kirino_engine} mod id (see MixinLate, mixins.actinium.kirino.json);
 * the target class is referenced by string, so no compile-time dependency on Kirino exists.
 */
@Mixin(targets = "com.cleanroommc.kirino.config.KirinoConfigHub", remap = false)
public abstract class MixinKirinoConfigHub {

    /**
     * @author Actinium
     * @reason Force Kirino into Headless mode so vanilla {@code renderWorld} (and therefore
     * the Actinium render pipeline) stays in charge; {@code KIRINO_CONFIG_HUB.enable} remains
     * untouched so Kirino's ECS/analysis runtime still initialises.
     */
    @Overwrite
    public boolean isEnableRenderDelegate() {
        return false;
    }
}
