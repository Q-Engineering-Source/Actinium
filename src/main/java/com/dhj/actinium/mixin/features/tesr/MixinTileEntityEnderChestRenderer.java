package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.VanillaModelMeshes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.model.ModelChest;
import net.minecraft.client.renderer.tileentity.TileEntityEnderChestRenderer;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(TileEntityEnderChestRenderer.class)
public abstract class MixinTileEntityEnderChestRenderer {

    @ModifyArg(
        method = "render(Lnet/minecraft/tileentity/TileEntityEnderChest;DDDFIF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntityEnderChestRenderer;bindTexture(Lnet/minecraft/util/ResourceLocation;)V"),
        index = 0
    )
    private ResourceLocation actinium$captureTexture(ResourceLocation texture, @Share("texture") LocalRef<ResourceLocation> boundTexture) {
        boundTexture.set(texture);
        return texture;
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/tileentity/TileEntityEnderChest;DDDFIF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/ModelChest;renderAll()V")
    )
    private void actinium$renderCachedChest(ModelChest model, Operation<Void> original,
                                            @Local(argsOnly = true) TileEntityEnderChest chest,
                                            @Local(argsOnly = true) int destroyStage,
                                            @Local(argsOnly = true, ordinal = 1) float alpha,
                                            @Share("texture") LocalRef<ResourceLocation> boundTexture) {
        if (destroyStage < 0 && alpha == 1.0f && chest.hasWorld() && VanillaModelMeshes.canRenderCached()) {
            VanillaModelMeshes.renderChest(model, boundTexture.get(), false);
        } else {
            original.call(model);
        }
    }
}
