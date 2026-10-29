package com.dhj.actinium.render.terrain.compile;

import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.util.Objects;

import static net.minecraft.block.material.Material.LAVA;
import static net.minecraft.block.material.Material.WATER;

/** A reusable vanilla buffer tagged with the chunk build and layer that own its output. */
public final class ChunkBufferBuilder extends BufferBuilder implements ChunkRenderInvocation {
    private final VintageChunkBuildContext buildContext;
    private final BlockRenderLayer renderLayer;

    private boolean invocationActive;
    private boolean allowFastRenderer;
    private boolean allowRenderPassOptimization;
    private boolean fluidRender;
    private IBlockState invocationState;
    private int dispatcherDepth;

    /** Creates the per-layer buffer used by the replacement chunk mesher. */
    public ChunkBufferBuilder(int initialCapacity, VintageChunkBuildContext buildContext, BlockRenderLayer renderLayer) {
        super(initialCapacity);
        this.buildContext = Objects.requireNonNull(buildContext, "buildContext");
        this.renderLayer = Objects.requireNonNull(renderLayer, "renderLayer");
    }

    @Override
    public VintageChunkBuildContext actinium$buildContext() {
        return this.buildContext;
    }

    @Override
    public BlockRenderLayer actinium$renderLayer() {
        return this.renderLayer;
    }

    @Override
    public void actinium$beginInvocation(boolean allowFastRenderer, boolean allowRenderPassOptimization,
                                         boolean fluidRender, IBlockState invocationState) {
        if (this.invocationActive) {
            throw new IllegalStateException("Chunk buffer already has an active block invocation");
        }
        IBlockState checkedInvocationState = Objects.requireNonNull(invocationState, "invocationState");

        this.invocationActive = true;
        this.allowFastRenderer = allowFastRenderer;
        this.allowRenderPassOptimization = allowRenderPassOptimization;
        this.fluidRender = fluidRender;
        this.invocationState = checkedInvocationState;
    }

    @Override
    public void actinium$endInvocation() {
        if (!this.invocationActive) {
            throw new IllegalStateException("Chunk buffer has no active block invocation");
        }
        if (this.dispatcherDepth != 0) {
            throw new IllegalStateException("Chunk buffer dispatcher depth did not return to zero");
        }

        this.invocationActive = false;
        this.allowFastRenderer = false;
        this.allowRenderPassOptimization = false;
        this.fluidRender = false;
        this.invocationState = null;
    }

    @Override
    public boolean actinium$isInvocationActive() {
        return this.invocationActive;
    }

    @Override
    public boolean actinium$enterDispatcherCall() {
        if (!this.invocationActive) {
            throw new IllegalStateException("Cannot enter a dispatcher call without a chunk invocation");
        }

        boolean outermost = this.dispatcherDepth == 0;
        this.dispatcherDepth++;
        return outermost;
    }

    @Override
    public void actinium$exitDispatcherCall() {
        if (this.dispatcherDepth == 0) {
            throw new IllegalStateException("No chunk dispatcher call is active");
        }

        this.dispatcherDepth--;
    }

    @Override
    public boolean actinium$canRenderFastModel(IBlockAccess blockAccess, boolean outermostCall) {
        return this.invocationActive
                && this.allowFastRenderer
                && outermostCall
                && blockAccess instanceof ActiniumBlockAccess;
    }

    @Override
    public boolean actinium$renderFastModel(IBakedModel model, IBlockState state, BlockPos pos,
                                            IBlockAccess blockAccess, boolean checkSides) {
        if (!(blockAccess instanceof ActiniumBlockAccess actiniumBlockAccess)) {
            throw new IllegalArgumentException("Fast chunk model rendering requires ActiniumBlockAccess");
        }

        return this.buildContext.getBlockRenderer().renderDispatchedModel(
                model, state, pos, actiniumBlockAccess, this.renderLayer, checkSides,
                this.allowRenderPassOptimization);
    }

    @Override
    public void actinium$beginVanillaCapture(BufferBuilder buffer, BlockPos pos, IBlockState state) {
        if (isFluidCapture(this.fluidRender, this.dispatcherDepth, this.invocationState, state)) {
            this.buildContext.beginVanillaFluidRender(buffer, pos, state);
        } else {
            this.buildContext.beginVanillaBlockRender(buffer, pos, state);
        }
    }

    @Override
    public void actinium$endVanillaCapture(BufferBuilder buffer) {
        this.buildContext.endVanillaRender(buffer);
    }

    static boolean isFluidCapture(boolean fluidInvocation, int dispatcherDepth,
                                  IBlockState invocationState, IBlockState state) {
        return state.getMaterial() == WATER
                || state.getMaterial() == LAVA
                || fluidInvocation && (dispatcherDepth == 1
                        || invocationState.equals(state));
    }
}
