package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the render of a dropped item to the item instancer.
 *
 * <p>{@code RenderItem.renderItem} is shared by the dropped item, the held item and the GUI, so the
 * instancer only takes the draw while {@link DroppedItemInstancer} is inside a
 * {@code RenderEntityItem.doRender} scope. The glint of the same draw is taken in the iris config's
 * {@code renderEffect} hook, which also owns the gbuffer condition that has to stay balanced around
 * it.</p>
 */
@Mixin(RenderItem.class)
public abstract class MixinRenderItemDroppedInstancing {

    @Shadow
    @Final
    private ItemColors itemColors;

    @Inject(
        method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void actinium$instanceDroppedItem(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        if (DroppedItemInstancer.renderItemPart(this.itemColors, stack, model) == DroppedItemInstancer.SKIP) {
            ci.cancel();
        }
    }

    @Inject(
        method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
        at = @At("RETURN")
    )
    private void actinium$reportDroppedItemDraw(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        DroppedItemInstancer.endPart();
    }
}
