package com.dhj.actinium.mixin.vintage.core.terrain;

import com.dhj.actinium.render.terrain.compile.ChunkBlockRendererRouter;
import com.dhj.actinium.render.terrain.compile.ChunkBufferBuilder;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
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

/**
 * Routes the model call inside {@code BlockRendererDispatcher.renderBlock} to the fast chunk
 * renderer while a section is meshed. Only that inner call is wrapped, so a mod that replaces or
 * injects into {@code renderBlock} itself (Snow Real Magic, ArchitectureCraft, Better Foliage) still
 * runs first and the fallback marker on the buffer decides whether the fast renderer may take over.
 */
@Mixin(BlockRendererDispatcher.class)
public class MixinBlockRendererDispatcher {
    @WrapOperation(
            method = "renderBlock",
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
    private boolean actinium$renderChunkBlockModel(
            BlockModelRenderer renderer,
            IBlockAccess world,
            IBakedModel model,
            IBlockState state,
            BlockPos pos,
            BufferBuilder buffer,
            boolean checkSides,
            Operation<Boolean> original
    ) {
        if (ChunkBlockRendererRouter.shouldUseFastRenderer(world, buffer)) {
            ChunkBufferBuilder chunkBuffer = (ChunkBufferBuilder)buffer;
            return chunkBuffer.getContext().getBlockRenderer()
                    .renderModel(model, state, pos, (ActiniumBlockAccess)world, chunkBuffer.getLayer(),
                            chunkBuffer.isAllowRenderPassOptimization());
        }
        return original.call(renderer, world, model, state, pos, buffer, checkSides);
    }
}
