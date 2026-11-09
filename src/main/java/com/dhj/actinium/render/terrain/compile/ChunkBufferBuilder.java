package com.dhj.actinium.render.terrain.compile;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;

/**
 * The vanilla buffer handed to {@code BlockRendererDispatcher} while a chunk section is meshed.
 *
 * <p>It carries the build context and render layer so the dispatcher hook can recognize calls made
 * on behalf of a chunk build, the {@code forceVanillaRendering} flag so the mesh task can keep a
 * single block (or a Fluidlogged fluid) on the original vanilla path, and the
 * {@code allowRenderPassOptimization} flag the fast renderer must honour when it takes over the
 * dispatcher's model call.</p>
 */
public class ChunkBufferBuilder extends BufferBuilder {
    private final VintageChunkBuildContext context;
    private final BlockRenderLayer layer;
    private boolean forceVanillaRendering;
    private boolean allowRenderPassOptimization = true;

    public ChunkBufferBuilder(int bufferSize, VintageChunkBuildContext context, BlockRenderLayer layer) {
        super(bufferSize);
        this.context = context;
        this.layer = layer;
    }

    public VintageChunkBuildContext getContext() {
        return this.context;
    }

    public BlockRenderLayer getLayer() {
        return this.layer;
    }

    /**
     * Returns whether this build must render the current block through the vanilla dispatcher
     * because a mod owns its geometry (Snow Real Magic, ArchitectureCraft) or because a
     * Fluidlogged fluid state is being rendered.
     */
    public boolean isForceVanillaRendering() {
        return this.forceVanillaRendering;
    }

    public void setForceVanillaRendering(boolean forceVanillaRendering) {
        this.forceVanillaRendering = forceVanillaRendering;
    }

    /**
     * Returns whether the fast renderer may merge quads into the cheapest render pass for this
     * buffer. A nested render on the same buffer keeps the pass selected by the outer call when it
     * re-dispatches the base block.
     */
    public boolean isAllowRenderPassOptimization() {
        return this.allowRenderPassOptimization;
    }

    public void setAllowRenderPassOptimization(boolean allowRenderPassOptimization) {
        this.allowRenderPassOptimization = allowRenderPassOptimization;
    }
}
