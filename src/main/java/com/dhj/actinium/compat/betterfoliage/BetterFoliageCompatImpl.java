package com.dhj.actinium.compat.betterfoliage;

import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import java.util.List;
import mods.betterfoliage.client.Client;
import mods.betterfoliage.client.Hooks;
import mods.betterfoliage.client.render.Utils;
import mods.octarinecore.client.render.AbstractBlockRenderingHandler;
import mods.octarinecore.client.render.BlockContext;
import mods.octarinecore.client.render.RendererHolder;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Adapts Better Foliage's vanilla RenderChunk callbacks to Actinium's replacement meshing path.
 */
public final class BetterFoliageCompatImpl implements BetterFoliageCompat {
    /**
     * Creates the adapter when the original Better Foliage API is present.
     */
    public BetterFoliageCompatImpl() {
    }

    @Override
    public boolean canRenderBlockInLayer(Block block, IBlockState state, BlockRenderLayer layer) {
        return Hooks.canRenderBlockInLayer(block, state, layer);
    }

    @Override
    public boolean renderVanillaBlock(
            BlockRendererDispatcher dispatcher,
            IBlockState state,
            BlockPos pos,
            IBlockAccess blockAccess,
            BufferBuilder buffer,
            BlockRenderLayer layer
    ) {
        return Hooks.renderWorldBlock(dispatcher, state, pos, blockAccess, buffer, layer);
    }

    @Override
    public boolean tryRenderFastBlock(
            IBlockState state,
            BlockPos pos,
            ActiniumBlockAccess blockAccess,
            VintageChunkBuildContext buildContext,
            BlockRenderLayer layer
    ) {
        if (!hasEligibleRenderer(state, pos, blockAccess, layer)) {
            return false;
        }

        BufferBuilder buffer = buildContext.getBufferForLayer(layer);
        buildContext.beginVanillaBlockRender(buffer, pos, state);
        try {
            Hooks.renderWorldBlock(
                    Minecraft.getMinecraft().getBlockRendererDispatcher(),
                    state,
                    pos,
                    blockAccess,
                    buffer,
                    layer
            );
        } finally {
            buildContext.endVanillaRender(buffer);
        }
        return true;
    }

    /**
     * Mirrors Better Foliage's renderer eligibility check so ordinary blocks retain Actinium's fast path.
     */
    private boolean hasEligibleRenderer(
            IBlockState state,
            BlockPos pos,
            IBlockAccess blockAccess,
            BlockRenderLayer layer
    ) {
        BlockContext context = RendererHolder.getBlockContext();
        context.set(blockAccess, pos);

        BlockRenderLayer targetCutoutLayer = Hooks.getTargetCutoutLayer();
        boolean baseRenderApplies = Utils.canRenderInLayer(state, layer)
                || layer == targetCutoutLayer
                && Utils.canRenderInLayer(state, Hooks.getOtherCutoutLayer());
        List<AbstractBlockRenderingHandler> renderers = Client.INSTANCE.getRenderers();
        for (AbstractBlockRenderingHandler renderer : renderers) {
            if (renderer.isEligible(context)
                    && (baseRenderApplies
                    || renderer.getAddToCutout() && layer == targetCutoutLayer)) {
                return true;
            }
        }
        return false;
    }
}
