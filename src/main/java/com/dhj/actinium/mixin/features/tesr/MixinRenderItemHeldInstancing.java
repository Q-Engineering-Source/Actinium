package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderItem.class)
public abstract class MixinRenderItemHeldInstancing {
    @WrapOperation(method = "renderItemModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/RenderItem;renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V"))
    private void actinium$instanceHeldItem(RenderItem renderer, ItemStack stack, IBakedModel model,
                                           Operation<Void> original, @Local(argsOnly = true) TransformType transform) {
        DroppedItemInstancer.renderHeldItem(renderer::renderQuads, renderer.itemColors, stack, model, transform,
            () -> original.call(renderer, stack, model));
    }
}
