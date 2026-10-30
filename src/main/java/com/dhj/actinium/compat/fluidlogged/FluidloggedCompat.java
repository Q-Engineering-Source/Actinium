package com.dhj.actinium.compat.fluidlogged;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import git.jbredwards.fluidlogged_api.api.block.IFluidloggable;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.ForgeHooksClient;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;

public class FluidloggedCompat {
    /**
     * The mod ID of Fluidlogged API.
     */
    public static final String MODID = "fluidlogged_api";
    public static final boolean IS_LOADED = Mods.FLUIDLOGGED_API;

    public static FluidState getEmptyFluidState() {
        return FluidState.EMPTY;
    }

    public static void renderFluidState(ActiniumBlockAccess blockAccess, BlockPos pos, IBlockState state, VintageChunkBuildContext context, BlockRendererDispatcher dispatcher) {
        FluidState fluidState = blockAccess.getFluidState(pos);
        if (fluidState != FluidState.EMPTY && (!(state.getBlock() instanceof IFluidloggable) || ((IFluidloggable)state.getBlock()).shouldFluidRender(blockAccess, pos, state, fluidState))) {
            IBlockState renderState = fluidState.getState().getActualState(blockAccess, pos);
            var block = renderState.getBlock();

            for (BlockRenderLayer layer : VintageChunkBuildContext.LAYERS) {
                if (block.canRenderInLayer(renderState, layer)) {
                    ForgeHooksClient.setRenderLayer(layer);
                    var buffer = context.getBufferForLayer(layer);
                    context.beginVanillaFluidRender(buffer, pos, renderState);
                    try {
                        dispatcher.renderBlock(renderState, pos, blockAccess, buffer);
                    } finally {
                        context.endVanillaRender(buffer);
                    }
                }
            }
        }
    }
}

