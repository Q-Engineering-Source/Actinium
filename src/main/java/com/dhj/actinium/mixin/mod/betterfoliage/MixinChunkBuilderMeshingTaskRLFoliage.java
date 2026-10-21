package com.dhj.actinium.mixin.mod.betterfoliage;

import betterfoliage.render.feature.RenderingHandler;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes RLFoliage block features through Actinium's current chunk meshing task.
 * RLFoliage still ships a Celeritas Mixin against the legacy org.taumc task name,
 * so without this adapter only its particle effects are active.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class MixinChunkBuilderMeshingTaskRLFoliage {
    @Unique
    private static final String EXECUTE_METHOD =
            "execute(Ldhj/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;"
                    + "Ldhj/embeddedt/embeddium/impl/util/task/CancellationToken;"
                    + ")Ldhj/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;";

    @Redirect(
            method = EXECUTE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;canRenderInLayer("
                            + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockRenderLayer;)Z",
                    remap = true
            )
    )
    private boolean actinium$betterFoliageCanRenderInLayer(
            Block block,
            IBlockState state,
            BlockRenderLayer layer
    ) {
        return RenderingHandler.canRenderBlockInLayer(block, state, layer);
    }

    @WrapOperation(
            method = EXECUTE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;renderBlock("
                            + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                            + "Lnet/minecraft/world/IBlockAccess;"
                            + "Lnet/minecraft/client/renderer/BufferBuilder;)Z",
                    remap = true
            )
    )
    private boolean actinium$betterFoliageWrapVanillaRenderBlock(
            BlockRendererDispatcher dispatcher,
            IBlockState state,
            BlockPos pos,
            IBlockAccess blockAccess,
            BufferBuilder buffer,
            Operation<Boolean> original,
            @Local(name = "layer") BlockRenderLayer layer,
            @Local(name = "buildContext") VintageChunkBuildContext buildContext
    ) {
        buildContext.beginVanillaBlockRenderContextOnly(buffer, pos, state);
        try {
            Boolean result = RenderingHandler.wrapRenderBlock(
                    () -> original.call(dispatcher, state, pos, blockAccess, buffer),
                    state,
                    pos,
                    blockAccess,
                    () -> buildContext.getBufferForLayer(layer),
                    layer
            );
            if (result == null) {
                return original.call(dispatcher, state, pos, blockAccess, buffer);
            }
            return result;
        } finally {
            buildContext.endVanillaRender(buffer);
        }
    }
}
