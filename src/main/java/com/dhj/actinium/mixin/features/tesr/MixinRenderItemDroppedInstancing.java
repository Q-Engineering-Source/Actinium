package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the render of a dropped item to the item instancer.
 *
 * <p>{@code RenderItem.renderItem} is shared by the dropped item, the held item and the GUI, so the
 * instancer only takes the draw while {@link DroppedItemInstancer} is inside a
 * {@code RenderEntityItem.doRender} scope. The capture goes through this class's own
 * {@code renderQuads}, and the glint layers are queued by {@link ModelPartBatcher} under the glint
 * texture and the two {@code ItemGlint} texture matrices; nothing here touches the vanilla render
 * state, so {@code RenderItemIrisMixin} keeps wrapping this method and keeps owning the state
 * boundary of a built-in item renderer. A returned {@link DroppedItemInstancer#RUN} leaves the whole
 * vanilla body in place, and a {@link DroppedItemInstancer#SKIP} cancels it after the batch agreed to
 * draw both the base geometry and the glint.</p>
 */
@Mixin(RenderItem.class)
public abstract class MixinRenderItemDroppedInstancing {

    @Inject(
        method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void actinium$instanceDroppedItem(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        final RenderItem renderItem = (RenderItem) (Object) this;
        if (DroppedItemInstancer.renderItemPart(renderItem::renderQuads, renderItem.itemColors, stack, model)
            == DroppedItemInstancer.SKIP) {
            DroppedItemInstancer.endPart();
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

    @Inject(method = "renderEffect(Lnet/minecraft/client/renderer/block/model/IBakedModel;)V", at = @At("HEAD"), cancellable = true)
    private void actinium$skipDisabledDroppedItemGlint(IBakedModel model, CallbackInfo ci) {
        if (!DroppedItemInstancer.shouldRenderEffect()) {
            ci.cancel();
        }
    }
}
