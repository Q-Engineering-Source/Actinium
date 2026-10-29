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
import com.dhj.actinium.render.terrain.compile.ChunkBufferBuilder;

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
                    ChunkBufferBuilder buffer = context.getChunkBufferForLayer(layer);
                    buffer.actinium$beginInvocation(false, false, true, renderState);
                    boolean captureStarted = false;
                    try {
                        context.beginVanillaFluidRender(buffer, pos, renderState);
                        captureStarted = true;
                        dispatcher.renderBlock(renderState, pos, blockAccess, buffer);
                    } finally {
                        try {
                            if (captureStarted) {
                                context.endVanillaRender(buffer);
                            }
                        } finally {
                            buffer.actinium$endInvocation();
                        }
                    }
                }
            }
        }
    }
}

