package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.DeferredEntityOverlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the armor glint from writing depth while a deferred replay runs.
 *
 * <p>Armor layers keep vanilla's inline path, but the glint pass restores depth writes with
 * {@code depthMask(true)} before it returns, which would let the replayed overlay write depth for
 * the rest of the glint shell.</p>
 */
@Mixin(LayerArmorBase.class)
public abstract class LayerArmorBaseOverlayDepthMixin {

    @WrapOperation(
        method = "renderEnchantedGlint",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;depthMask(Z)V")
    )
    private static void actinium$keepDepthReadOnly(boolean flag, Operation<Void> original) {
        original.call(DeferredEntityOverlay.isReplaying() ? false : flag);
    }
}
