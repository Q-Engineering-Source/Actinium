package com.dhj.actinium.compat.ctm;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import team.chisel.ctm.client.asm.CTMCoreMethods;

/** Keeps CTM texture layer filtering independent of the shader pack's terrain pass. */
public final class CtmLayerCompat {
    private CtmLayerCompat() {
    }

    public static boolean requiresSourceLayers(IBlockState state, IBlockAccess world, BlockPos pos,
                                               BlockRenderLayer targetLayer) {
        if (!Mods.CTM) {
            return false;
        }

        IBlockState actualState = world.getWorldType() == WorldType.DEBUG_ALL_BLOCK_STATES
                ? state : state.getActualState(world, pos);
        return ModelCheck.hasLayerFilter(actualState, targetLayer);
    }

    /** Runs the draw with each Forge layer the block normally exposes. The caller selects the output buffer. */
    public static void renderSourceLayers(IBlockState state, Runnable draw) {
        BlockRenderLayer previousLayer = MinecraftForgeClient.getRenderLayer();
        try {
            ForgeHooksClient.setRenderLayer(null);
            for (BlockRenderLayer sourceLayer : BlockRenderLayer.values()) {
                if (state.getBlock().canRenderInLayer(state, sourceLayer)) {
                    ForgeHooksClient.setRenderLayer(sourceLayer);
                    draw.run();
                }
            }
        } finally {
            ForgeHooksClient.setRenderLayer(previousLayer);
        }
    }

    private static final class ModelCheck {
        private static boolean hasLayerFilter(IBlockState state, BlockRenderLayer layer) {
            // CTM returns null for states whose baked model it does not handle.
            return CTMCoreMethods.canRenderInLayer(state, layer) != null;
        }
    }
}
