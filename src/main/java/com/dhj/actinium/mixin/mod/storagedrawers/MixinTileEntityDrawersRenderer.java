package com.dhj.actinium.mixin.mod.storagedrawers;

import com.dhj.actinium.compat.storagedrawers.StorageDrawersLighting;
import com.jaquadro.minecraft.chameleon.render.ChamRender;
import com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityDrawers;
import com.jaquadro.minecraft.storagedrawers.client.renderer.TileEntityDrawersRenderer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Storage Drawers' standard item lights in the view-space matrix during item rendering. */
@Mixin(value = TileEntityDrawersRenderer.class, remap = false)
public abstract class MixinTileEntityDrawersRenderer {
    @Inject(method = "renderFastItem", at = @At("HEAD"), remap = false)
    private void actinium$captureItemLightingMatrix(
        ChamRender chamRender,
        ItemStack stack,
        TileEntityDrawers drawers,
        IBlockState blockState,
        int drawerIndex,
        EnumFacing facing,
        float animationProgress,
        float partialTicks,
        CallbackInfo ci
    ) {
        StorageDrawersLighting.INSTANCE.captureRootModelView();
    }

    @Redirect(
        method = "renderFastItem",
        remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderHelper;enableStandardItemLighting()V",
            remap = true
        )
    )
    private void actinium$enableItemLightingFromRootMatrix() {
        StorageDrawersLighting.INSTANCE.enableStandardItemLightingAtRootModelView();
    }
}
