package com.dhj.actinium.render.terrain.compile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Routes one dispatcher-selected model while retaining vanilla rendering as the fallback. */
public interface ChunkModelRouting {
    /** Renders a selected model or invokes the original BlockModelRenderer call. */
    boolean render(BlockModelRenderer renderer, IBlockAccess blockAccess, IBakedModel model,
                   IBlockState state, BlockPos pos, BufferBuilder buffer, boolean checkSides,
                   Operation<Boolean> original);
}
