package com.gtnewhorizons.angelica.rendering;

import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.iris.IrisDisplayListState;
import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.coderbot.iris.block_rendering.BlockMaterialMapping;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;

/** Gives blocks drawn outside chunk meshing their block.properties id on mc_Entity. */
public final class BlockMaterialAttribute {

    private BlockMaterialAttribute() {
    }

    public static void set(Block block, int metadata) {
        IrisDisplayListState.recordBlockEntityAttribute(block, metadata);
        if (!shadersActive()) return;
        IrisDisplayListState.runUnrecorded(() ->
            GLSMCurrentAttribs.glVertexAttrib2s(ProgramCreator.MC_ENTITY, (short) blockMaterialId(block, metadata), (short) 0));
    }

    public static void reset() {
        IrisDisplayListState.recordBlockEntityAttribute(null, 0);
        if (!shadersActive()) return;
        IrisDisplayListState.runUnrecorded(() ->
            GLSMCurrentAttribs.glVertexAttrib2s(ProgramCreator.MC_ENTITY, (short) -1, (short) -1));
    }

    static boolean shadersActive() {
        return GLStateManager.isMainThread() && IrisApi.getInstance().isShaderPackInUse();
    }

    public static boolean skipDirectionalShading() {
        if (!shadersActive() || !BlockRenderingSettings.INSTANCE.shouldDisableDirectionalShading()) return false;
        return switch (GbufferPrograms.getCurrentPhase()) {
            case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_CUTOUT_MIPPED, TERRAIN_TRANSLUCENT -> true;
            default -> false;
        };
    }

    public static int blockMaterialId(Block block, int metadata) {
        final Reference2ObjectMap<Block, Int2IntMap> blockMetaMatches = BlockRenderingSettings.INSTANCE.getBlockMetaMatches();
        if (blockMetaMatches == null) return -1;

        final Int2IntMap metaMap = blockMetaMatches.get(block);
        return metaMap != null ? BlockMaterialMapping.resolveId(metaMap, metadata) : -1;
    }

    public static WorldRenderingPhase renderingPhase(IBlockState state, boolean fading) {
        if (fading) return WorldRenderingPhase.TERRAIN_TRANSLUCENT;
        if (state == null) return WorldRenderingPhase.TERRAIN_CUTOUT;
        final Block block = state.getBlock();
        final var overrides = BlockRenderingSettings.INSTANCE.getBlockTypeIds();
        final BlockRenderLayer override = overrides == null ? null : overrides.get(block);
        if (override != null) {
            return switch (override) {
                case SOLID -> WorldRenderingPhase.TERRAIN_SOLID;
                case CUTOUT -> WorldRenderingPhase.TERRAIN_CUTOUT;
                case CUTOUT_MIPPED -> WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED;
                case TRANSLUCENT -> WorldRenderingPhase.TERRAIN_TRANSLUCENT;
            };
        }
        if (block.getRenderLayer() == net.minecraft.util.BlockRenderLayer.TRANSLUCENT) return WorldRenderingPhase.TERRAIN_TRANSLUCENT;
        return block.isOpaqueCube(state) ? WorldRenderingPhase.TERRAIN_SOLID : WorldRenderingPhase.TERRAIN_CUTOUT;
    }
}
