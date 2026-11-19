package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.coderbot.iris.uniforms.ItemIdManager;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.entity.item.EntityItem;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderEntityItem.class)
public class RenderEntityItemIrisMixin {
    /**
     * Sets the item id the shader uniforms read and opens the scope that lets the item instancer
     * take the draws. Both are restored in a finally block: a renderer that throws part way through
     * an item would otherwise leave the next GUI item inside a dropped item scope.
     */
    @WrapMethod(method = "doRender(Lnet/minecraft/entity/item/EntityItem;DDDFF)V")
    private void actinium$scopeDroppedItem(
        EntityItem entity,
        double x,
        double y,
        double z,
        float entityYaw,
        float partialTicks,
        Operation<Void> original
    ) {
        ItemIdManager.setItemId(entity.getItem());
        DroppedItemInstancer.enterScope(entity);
        try {
            original.call(entity, x, y, z, entityYaw, partialTicks);
        } finally {
            DroppedItemInstancer.exitScope();
            ItemIdManager.resetItemId();
        }
    }
}
