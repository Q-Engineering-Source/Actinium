package com.dhj.actinium.compat.betterfoliage;

import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Boundary for the original Better Foliage 1.12.2 rendering hooks used by Actinium's chunk mesher.
 */
public interface BetterFoliageCompat {
    /**
     * Applies Better Foliage's additional cutout-layer decision during terrain layer selection.
     *
     * @param block block whose render layer is being checked
     * @param state state being rendered
     * @param layer current terrain layer
     * @return whether Better Foliage wants this state processed for the layer
     */
    boolean canRenderBlockInLayer(Block block, IBlockState state, BlockRenderLayer layer);

    /**
     * Routes vanilla-dispatcher rendering through Better Foliage's replacement for RenderChunk calls.
     *
     * @param dispatcher vanilla block renderer
     * @param state state being rendered
     * @param pos block position
     * @param blockAccess current chunk snapshot
     * @param buffer layer buffer
     * @param layer current terrain layer
     * @return whether Better Foliage rendered the block
     */
    boolean renderVanillaBlock(
            BlockRendererDispatcher dispatcher,
            IBlockState state,
            BlockPos pos,
            IBlockAccess blockAccess,
            BufferBuilder buffer,
            BlockRenderLayer layer
    );

    /**
     * Uses Better Foliage's dispatcher only when one of its feature renderers handles this block.
     *
     * @param state state being rendered
     * @param pos block position
     * @param blockAccess current chunk snapshot
     * @param buildContext active chunk build context
     * @param layer current terrain layer
     * @return true when Better Foliage handled this block and the fast renderer should be skipped
     */
    boolean tryRenderFastBlock(
            IBlockState state,
            BlockPos pos,
            ActiniumBlockAccess blockAccess,
            VintageChunkBuildContext buildContext,
            BlockRenderLayer layer
    );
}
