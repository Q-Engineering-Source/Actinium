package com.dhj.actinium.mixin.mod.betterfoliage;

import com.dhj.actinium.compat.betterfoliage.BetterFoliageCompat;
import com.dhj.actinium.compat.betterfoliage.BetterFoliageCompatImpl;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.pipeline.VintageBlockRenderer;
import com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
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
 * Routes original Better Foliage hooks through Actinium's replacement chunk mesher.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class MixinChunkBuilderMeshingTaskBetterFoliage {
    @Unique
    private static final String EXECUTE_METHOD =
            "execute(Ldhj/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;"
                    + "Ldhj/embeddedt/embeddium/impl/util/task/CancellationToken;"
                    + ")Ldhj/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;";

    @Unique
    private static final BetterFoliageCompat ACTINIUM$BETTER_FOLIAGE_COMPAT =
            new BetterFoliageCompatImpl();

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
        return ACTINIUM$BETTER_FOLIAGE_COMPAT.canRenderBlockInLayer(block, state, layer);
    }

    @WrapOperation(
            method = EXECUTE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRenderer;renderBlock("
                            + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                            + "Lcom/dhj/actinium/world/cloned/ActiniumBlockAccess;"
                            + "Lnet/minecraft/util/BlockRenderLayer;)V",
                    remap = false
            )
    )
    private void actinium$betterFoliageRenderFastBlock(
            VintageBlockRenderer renderer,
            IBlockState state,
            BlockPos pos,
            ActiniumBlockAccess blockAccess,
            BlockRenderLayer layer,
            Operation<Void> original,
            @Local(name = "buildContext") VintageChunkBuildContext buildContext
    ) {
        if (!ACTINIUM$BETTER_FOLIAGE_COMPAT.tryRenderFastBlock(
                state, pos, blockAccess, buildContext, layer
        )) {
            original.call(renderer, state, pos, blockAccess, layer);
        }
    }

    @WrapOperation(
            method = EXECUTE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRenderer;renderBlock("
                            + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                            + "Lcom/dhj/actinium/world/cloned/ActiniumBlockAccess;"
                            + "Lnet/minecraft/util/BlockRenderLayer;Z)V",
                    remap = false
            )
    )
    private void actinium$betterFoliageRenderFastBlockWithOptimization(
            VintageBlockRenderer renderer,
            IBlockState state,
            BlockPos pos,
            ActiniumBlockAccess blockAccess,
            BlockRenderLayer layer,
            boolean allowRenderPassOptimization,
            Operation<Void> original,
            @Local(name = "buildContext") VintageChunkBuildContext buildContext
    ) {
        if (!ACTINIUM$BETTER_FOLIAGE_COMPAT.tryRenderFastBlock(
                state, pos, blockAccess, buildContext, layer
        )) {
            original.call(renderer, state, pos, blockAccess, layer, allowRenderPassOptimization);
        }
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
    private boolean actinium$betterFoliageRenderVanillaBlock(
            BlockRendererDispatcher dispatcher,
            IBlockState state,
            BlockPos pos,
            IBlockAccess blockAccess,
            BufferBuilder buffer,
            Operation<Boolean> original,
            @Local(name = "layer") BlockRenderLayer layer
    ) {
        return ACTINIUM$BETTER_FOLIAGE_COMPAT.renderVanillaBlock(
                dispatcher, state, pos, blockAccess, buffer, layer
        );
    }
}
