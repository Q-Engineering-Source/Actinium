package com.dhj.actinium.mixin.mod.revoui;

import com.dhj.actinium.render.RevoScreenEffectsGradient;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Moves Revo UI's full-screen gradient ahead of the HUD so it cannot darken HUD elements.
 *
 * <p>Exception: while a HUD cache capture window is active (Gnetum / StellarCore, mirrored
 * to {@link GLSMConfig#hudCacheOverride}) the gradient draws immediately into the cache
 * framebuffer; deferring there would re-register it only on the one pass that runs this
 * listener, so the replayed gradient would flicker at 1/numberOfPasses of the frame rate,
 * while drawing into the cache reproduces Gnetum's known-good cached-blit behavior.
 */
@Pseudo
@Mixin(targets = "neofontrender.addons.effects.ScreenEffectsRenderer", remap = false)
public abstract class MixinScreenEffectsGradientRelocation {
    @Redirect(
        method = "afterGameOverlay",
        at = @At(
            value = "INVOKE",
            target = "Lneofontrender/addons/effects/ScreenEffectsRenderer;drawGradient(IIF)V"
        ),
        remap = false
    )
    // Capture only actual gradient draws; frames without one must not refresh the pending value.
    private void actinium$deferGradient(int width, int height, float progress) {
        if (GLSMConfig.hudCacheOverride) {
            ScreenEffectsRendererInvoker.actinium$drawGradient(width, height, progress);
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        RevoScreenEffectsGradient.defer(
            width,
            height,
            progress,
            minecraft.world,
            minecraft.currentScreen
        );
    }
}
