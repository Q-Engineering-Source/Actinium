package com.dhj.actinium.render.terrain.compile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Preserves the model selected by BlockRendererDispatcher and its two block-state stages. */
public final class DispatchedBlockModel {
    private static final ExtendedStateResolver DEFAULT_RESOLVER =
            (state, blockAccess, pos) -> state.getBlock().getExtendedState(state, blockAccess, pos);

    @Nullable
    private IBakedModel model;
    @Nullable
    private IBlockState dispatcherState;
    @Nullable
    private IBlockState extendedState;

    /** Creates the reusable prepared-model holder owned by one chunk renderer. */
    public DispatchedBlockModel() {
    }

    /** Extends the dispatcher-resolved state exactly once while retaining the dispatcher-selected model. */
    public void prepare(IBakedModel model, IBlockState dispatcherState, IBlockAccess blockAccess, BlockPos pos) {
        this.prepare(model, dispatcherState, blockAccess, pos, DEFAULT_RESOLVER);
    }

    /** Populates the holder through a supplied state resolver for direct behavior testing. */
    void prepare(IBakedModel model, IBlockState dispatcherState, IBlockAccess blockAccess, BlockPos pos,
                 ExtendedStateResolver resolver) {
        if (this.model != null) {
            throw new IllegalStateException("Dispatched model was not cleared after its previous render");
        }

        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(dispatcherState, "dispatcherState");
        IBlockState extendedState = Objects.requireNonNull(resolver, "resolver")
                .resolve(dispatcherState, blockAccess, pos);
        this.model = model;
        this.dispatcherState = dispatcherState;
        this.extendedState = Objects.requireNonNull(extendedState, "extendedState");
    }

    /** Releases state references after the render invocation has finished. */
    public void clear() {
        this.model = null;
        this.dispatcherState = null;
        this.extendedState = null;
    }

    /** Returns the exact model selected inside BlockRendererDispatcher. */
    public IBakedModel model() {
        return Objects.requireNonNull(this.model, "dispatched model is not prepared");
    }

    /** Returns the state passed by BlockRendererDispatcher after its actual-state rule. */
    public IBlockState dispatcherState() {
        return Objects.requireNonNull(this.dispatcherState, "dispatched model is not prepared");
    }

    /** Returns the state supplied to baked-model quad lookup. */
    public IBlockState extendedState() {
        return Objects.requireNonNull(this.extendedState, "dispatched model is not prepared");
    }

    /** Gets quads from the selected model using the single extended state resolved for this invocation. */
    public List<BakedQuad> getQuads(EnumFacing face, long random) {
        return this.model().getQuads(this.extendedState(), face, random);
    }

    /** Resolves the Forge extended state for one already-resolved dispatcher state. */
    @FunctionalInterface
    interface ExtendedStateResolver {
        /** Resolves extended properties without re-running the actual-state or model-selection steps. */
        IBlockState resolve(IBlockState dispatcherState, IBlockAccess blockAccess, BlockPos pos);
    }
}
