package com.dhj.actinium.api.render.terrain;

import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Transforms the baked quads of a block before terrain chunk upload.
 *
 * <p>Actinium calls registered transformers after the block model has produced
 * quads and before those quads are uploaded. This lets addons implement
 * runtime block quad work such as CTM or emissive overlays without Mixins into
 * the terrain renderer.</p>
 *
 * <p>Implementations must return a non-null list. An empty final result tells
 * the renderer to skip the current side; a non-empty result replaces the quads
 * passed to later transformers and to the renderer.</p>
 */
public interface BlockQuadTransformer {
    /**
     * Transforms the quads produced for one block face or the unassigned quads
     * of a block model.
     *
     * @param blockAccess thread-safe access to the block state and neighbors
     * @param side the face these quads belong to, or {@code null} for unassigned quads
     * @return must not be {@code null}; an empty final result tells the
     *         renderer to skip this side
     */
    List<BakedQuad> transform(IBlockState state, BlockPos pos,
                              ActiniumBlockAccess blockAccess, BlockRenderLayer layer,
                              EnumFacing side, List<BakedQuad> quads);
}
