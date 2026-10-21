package com.dhj.actinium.render.terrain.compile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Carries one chunk builder's routing policy and render state through the vanilla dispatcher. */
public interface ChunkRenderInvocation {
    /** Returns the build context that owns this per-layer buffer. */
    VintageChunkBuildContext actinium$buildContext();

    /** Returns the render layer whose material owns this buffer. */
    BlockRenderLayer actinium$renderLayer();

    /** Starts one top-level block invocation before calling BlockRendererDispatcher. */
    void actinium$beginInvocation(boolean allowFastRenderer, boolean allowRenderPassOptimization,
                                  boolean fluidRender, IBlockState invocationState);

    /** Ends the active block invocation after the dispatcher and compatibility hooks return. */
    void actinium$endInvocation();

    /** Returns whether this buffer currently belongs to a chunk block invocation. */
    boolean actinium$isInvocationActive();

    /** Enters one dispatcher model call and reports whether it is the outer call. */
    boolean actinium$enterDispatcherCall();

    /** Leaves the dispatcher model call entered by {@link #actinium$enterDispatcherCall()}. */
    void actinium$exitDispatcherCall();

    /** Decides whether this model call may use Actinium's renderer. */
    boolean actinium$canRenderFastModel(IBlockAccess blockAccess, boolean outermostCall);

    /** Renders the already selected model and resolved state through Actinium's model pipeline. */
    boolean actinium$renderFastModel(IBakedModel model, IBlockState state, BlockPos pos,
                                     IBlockAccess blockAccess, boolean checkSides);

    /** Captures shader metadata while the original vanilla model renderer writes to this buffer. */
    void actinium$beginVanillaCapture(BufferBuilder buffer, BlockPos pos, IBlockState state);

    /** Restores the quad metadata context after vanilla rendering, including when it throws. */
    void actinium$endVanillaCapture(BufferBuilder buffer);
}
