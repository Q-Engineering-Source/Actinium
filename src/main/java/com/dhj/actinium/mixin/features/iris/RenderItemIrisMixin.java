package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.render.FastLitItemDisplayListCache;
import com.dhj.actinium.render.ItemRenderStateBoundary;
import com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderItem.class)
public class RenderItemIrisMixin {
    @WrapMethod(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V")
    private void actinium$preserveBuiltInRendererState(
        ItemStack stack,
        IBakedModel model,
        Operation<Void> original
    ) {
        if (!model.isBuiltInRenderer()) {
            original.call(stack, model);
            return;
        }

        ItemRenderStateBoundary.begin();
        try {
            original.call(stack, model);
        } finally {
            ItemRenderStateBoundary.end();
        }
    }

    @Inject(method = "renderEffect(Lnet/minecraft/client/renderer/block/model/IBakedModel;)V", at = @At("HEAD"))
    void actinium$beginItemGlint(IBakedModel model, CallbackInfo ci) {
        DroppedItemInstancer.beginGlintScope();
    }

    @Inject(method = "renderEffect(Lnet/minecraft/client/renderer/block/model/IBakedModel;)V", at = @At("RETURN"))
    void actinium$endItemGlint(IBakedModel model, CallbackInfo ci) {
        DroppedItemInstancer.endGlintScope();
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void actinium$clearFastLitItemDisplayLists(IResourceManager resourceManager, CallbackInfo ci) {
        FastLitItemDisplayListCache.clear();
    }
}
