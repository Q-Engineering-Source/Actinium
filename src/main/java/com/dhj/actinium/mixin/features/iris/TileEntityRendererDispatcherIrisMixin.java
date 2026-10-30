package com.dhj.actinium.mixin.features.iris;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRendererDispatcherIrisMixin {
    @Inject(
        method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V",
        at = @At("HEAD")
    )
    private void actinium$setBlockEntityId(
        TileEntity tileEntity,
        double x,
        double y,
        double z,
        float partialTicks,
        int destroyStage,
        float alpha,
        CallbackInfo ci
    ) {
        CapturedRenderingState.INSTANCE.setCurrentBlockEntity(this.actinium$getBlockEntityId(tileEntity));
    }

    @Inject(
        method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V",
        at = @At("RETURN")
    )
    private void actinium$resetBlockEntityId(
        TileEntity tileEntity,
        double x,
        double y,
        double z,
        float partialTicks,
        int destroyStage,
        float alpha,
        CallbackInfo ci
    ) {
        CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
    }

    @Unique
    private int actinium$getBlockEntityId(TileEntity tileEntity) {
        if (tileEntity == null) {
            return 0;
        }

        Block block = tileEntity.getBlockType();
        if (block == null) {
            return 0;
        }

        int nbtBlockId = BlockRenderingSettings.INSTANCE.resolveBlockNbtId(block, tileEntity);
        if (nbtBlockId != -1) {
            return Math.max(0, nbtBlockId);
        }

        Reference2ObjectMap<Block, Int2IntMap> blockMetaMatches = BlockRenderingSettings.INSTANCE.getBlockMetaMatches();
        if (blockMetaMatches == null) {
            return 0;
        }

        Int2IntMap metaMap = blockMetaMatches.get(block);
        if (metaMap == null) {
            return 0;
        }

        return Math.max(0, metaMap.get(tileEntity.getBlockMetadata()));
    }
}
