package com.dhj.actinium.mixin.vintage.core.startup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Falls back to a sane value when {@code getFOVModifier} returns an invalid (≤ 0) result:
 * during startup {@code updateFovModifierHand} may not have run yet, leaving
 * {@code fovModifierHand} at its initial 0, so the first world frame can produce fovy=0
 * {@code gluPerspective} calls and an ∞ projection matrix (fullscreen cloud quads and
 * similar artifacts). Values ≤ 0 are replaced with {@code fovSetting} (verified not to
 * affect the rest of the frame).
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererFovFallback {

    @Inject(method = "getFOVModifier(FZ)F", at = @At("RETURN"), cancellable = true)
    private void actinium$fallbackInvalidFov(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValue() <= 0.0F) {
            cir.setReturnValue(Minecraft.getMinecraft().gameSettings.fovSetting);
        }
    }
}
