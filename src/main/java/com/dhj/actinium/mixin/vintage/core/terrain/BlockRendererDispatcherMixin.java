package com.dhj.actinium.mixin.vintage.core.terrain;

import com.dhj.actinium.render.terrain.compile.ChunkModelRoutingHolder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Redirects chunk-build model calls through the selected per-buffer render policy. */
@Mixin(BlockRendererDispatcher.class)
public abstract class BlockRendererDispatcherMixin {
    @WrapOperation(
            method = "renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/BufferBuilder;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BlockModelRenderer;renderModel("
                            + "Lnet/minecraft/world/IBlockAccess;"
                            + "Lnet/minecraft/client/renderer/block/model/IBakedModel;"
                            + "Lnet/minecraft/block/state/IBlockState;"
                            + "Lnet/minecraft/util/math/BlockPos;"
                            + "Lnet/minecraft/client/renderer/BufferBuilder;Z)Z"
            )
    )
    private boolean actinium$routeChunkModel(
            BlockModelRenderer renderer,
            IBlockAccess blockAccess,
            IBakedModel model,
            IBlockState state,
            BlockPos pos,
            BufferBuilder buffer,
            boolean checkSides,
            Operation<Boolean> original
    ) {
        return ChunkModelRoutingHolder.get().render(
                renderer, blockAccess, model, state, pos, buffer, checkSides, original);
    }
}
