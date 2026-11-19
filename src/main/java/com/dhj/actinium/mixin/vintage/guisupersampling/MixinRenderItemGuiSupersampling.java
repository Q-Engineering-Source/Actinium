package com.dhj.actinium.mixin.vintage.guisupersampling;

import com.dhj.actinium.render.GuiBlockSupersampling;
import com.dhj.actinium.render.RenderingState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Supersamples the item atlas while a stack is drawn into a GUI slot. */
@Mixin(RenderItem.class)
public class MixinRenderItemGuiSupersampling {

    @WrapMethod(method = "renderItemIntoGUI(Lnet/minecraft/item/ItemStack;II)V")
    private void actinium$supersampleGuiItem(ItemStack stack, int x, int y, Operation<Void> original) {
        final boolean previousItem = RenderingState.isRenderingGuiItem();
        RenderingState.setRenderingGuiItem(true);
        final int previousSamples = GuiBlockSupersampling.begin();
        try {
            original.call(stack, x, y);
        } finally {
            GuiBlockSupersampling.end(previousSamples);
            RenderingState.setRenderingGuiItem(previousItem);
        }
    }
}
