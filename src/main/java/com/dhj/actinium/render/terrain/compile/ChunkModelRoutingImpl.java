package com.dhj.actinium.render.terrain.compile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Implements the chunk-only fast path and keeps every other dispatcher call vanilla. */
public final class ChunkModelRoutingImpl implements ChunkModelRouting {
    /** Creates the stateless router installed at the dispatcher injection point. */
    public ChunkModelRoutingImpl() {
    }

    @Override
    public boolean render(BlockModelRenderer renderer, IBlockAccess blockAccess, IBakedModel model,
                          IBlockState state, BlockPos pos, BufferBuilder buffer, boolean checkSides,
                          Operation<Boolean> original) {
        if (!(buffer instanceof ChunkRenderInvocation invocation) || !invocation.actinium$isInvocationActive()) {
            return original.call(renderer, blockAccess, model, state, pos, buffer, checkSides);
        }

        boolean outermostCall = invocation.actinium$enterDispatcherCall();
        try {
            if (invocation.actinium$canRenderFastModel(blockAccess, outermostCall)) {
                return invocation.actinium$renderFastModel(model, state, pos, blockAccess, checkSides);
            }

            invocation.actinium$beginVanillaCapture(buffer, pos, state);
            try {
                return original.call(renderer, blockAccess, model, state, pos, buffer, checkSides);
            } finally {
                invocation.actinium$endVanillaCapture(buffer);
            }
        } finally {
            invocation.actinium$exitDispatcherCall();
        }
    }
}
