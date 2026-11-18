package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.VanillaModelMeshes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelSkeletonHead;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(TileEntitySkullRenderer.class)
public abstract class MixinTileEntitySkullRenderer {

    @ModifyArg(
        method = "renderSkull(FFFLnet/minecraft/util/EnumFacing;FILcom/mojang/authlib/GameProfile;IF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntitySkullRenderer;bindTexture(Lnet/minecraft/util/ResourceLocation;)V"),
        index = 0
    )
    private ResourceLocation actinium$captureTexture(ResourceLocation texture, @Share("texture") LocalRef<ResourceLocation> boundTexture) {
        boundTexture.set(texture);
        return texture;
    }

    @WrapOperation(
        method = "renderSkull(FFFLnet/minecraft/util/EnumFacing;FILcom/mojang/authlib/GameProfile;IF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V")
    )
    private void actinium$renderCachedSkull(ModelBase model, Entity entity, float limbSwing, float limbSwingAmount,
                                           float age, float yaw, float pitch, float scale, Operation<Void> original,
                                           @Local(argsOnly = true, ordinal = 0) int skullType,
                                           @Local(argsOnly = true, ordinal = 1) int destroyStage,
                                           @Share("texture") LocalRef<ResourceLocation> boundTexture) {
        if (destroyStage < 0 && model instanceof ModelSkeletonHead head && VanillaModelMeshes.canRenderCached()) {
            VanillaModelMeshes.renderSkull(head, boundTexture.get(), yaw, pitch, scale, skullType == 3);
        } else {
            original.call(model, entity, limbSwing, limbSwingAmount, age, yaw, pitch, scale);
        }
    }
}
