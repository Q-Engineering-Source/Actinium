package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.DeferredEntityOverlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerWitherAura;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the wither aura from writing depth while its deferred replay runs; the layer enables
 * depth writes before it draws and never restores the previous value.
 */
@Mixin(LayerWitherAura.class)
public abstract class LayerWitherAuraOverlayDepthMixin {

    @WrapOperation(
        method = "doRenderLayer(Lnet/minecraft/entity/boss/EntityWither;FFFFFFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;depthMask(Z)V")
    )
    private void actinium$keepDepthReadOnly(boolean flag, Operation<Void> original) {
        original.call(DeferredEntityOverlay.isReplaying() ? false : flag);
    }
}
