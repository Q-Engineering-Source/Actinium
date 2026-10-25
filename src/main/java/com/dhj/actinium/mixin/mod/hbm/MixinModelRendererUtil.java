package com.dhj.actinium.mixin.mod.hbm;

import com.dhj.actinium.compat.hbm.HbmModelRenderCompat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.model.ModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Bridges HBM's slicing eligibility check to Actinium's model rendering history.
 */
@Mixin(targets = "com.hbm.render.util.ModelRendererUtil", remap = false)
public abstract class MixinModelRendererUtil {
    @ModifyExpressionValue(
        method = "generateList",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/model/ModelRenderer;compiled:Z",
            remap = true
        )
    )
    private static boolean actinium$includeBatchedModelParts(
        boolean original, @Local(argsOnly = true) ModelRenderer renderer
    ) {
        return HbmModelRenderCompat.isReadyForSlicing(original, renderer);
    }
}
