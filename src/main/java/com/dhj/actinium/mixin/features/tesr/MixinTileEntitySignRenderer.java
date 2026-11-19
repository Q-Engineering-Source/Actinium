package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.VanillaModelMeshes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.model.ModelSign;
import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TileEntitySignRenderer.class)
public abstract class MixinTileEntitySignRenderer {

    @Shadow @Final private static ResourceLocation SIGN_TEXTURE;

    @WrapOperation(
        method = "render(Lnet/minecraft/tileentity/TileEntitySign;DDDFIF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/ModelSign;renderSign()V")
    )
    private void actinium$renderCachedBoard(ModelSign model, Operation<Void> original, @Local(argsOnly = true) int destroyStage) {
        if (destroyStage < 0 && VanillaModelMeshes.canRenderCached()) {
            VanillaModelMeshes.renderSign(model, SIGN_TEXTURE);
        } else {
            original.call(model);
        }
    }
}
