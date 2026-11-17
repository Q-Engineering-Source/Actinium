package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.DeferredEntityOverlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerCreeperCharge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the charged creeper aura from writing depth while its deferred replay runs.
 *
 * <p>The layer opens with {@code depthMask(!entity.isInvisible())} and closes with the inverse, so
 * the depth state the replay sets before the call is overwritten inside the layer itself.</p>
 */
@Mixin(LayerCreeperCharge.class)
public abstract class LayerCreeperChargeOverlayDepthMixin {

    @WrapOperation(
        method = "doRenderLayer(Lnet/minecraft/entity/monster/EntityCreeper;FFFFFFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;depthMask(Z)V")
    )
    private void actinium$keepDepthReadOnly(boolean flag, Operation<Void> original) {
        original.call(DeferredEntityOverlay.isReplaying() ? false : flag);
    }
}
