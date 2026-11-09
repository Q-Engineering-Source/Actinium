package com.dhj.actinium.render.terrain.compile;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.world.IBlockAccess;

/**
 * Decides whether a {@code BlockRendererDispatcher.renderBlock} call may be served by
 * {@code VintageBlockRenderer}.
 *
 * <p>The dispatcher itself is on the vanilla rendering path for the whole game, so the fast
 * renderer is only allowed when all four conditions hold: the player enabled it, the world is a
 * chunk-build snapshot whose blocks can be meshed off-thread, the buffer belongs to a chunk build,
 * and the current block is not marked for the vanilla fallback.</p>
 */
public final class ChunkBlockRendererRouter {
    private ChunkBlockRendererRouter() {
    }

    public static boolean shouldUseFastRenderer(IBlockAccess world, BufferBuilder buffer) {
        return ActiniumRuntime.options().performance.useFastBlockRenderer
                && world instanceof ActiniumBlockAccess
                && buffer instanceof ChunkBufferBuilder chunkBuffer
                && !chunkBuffer.isForceVanillaRendering();
    }
}
