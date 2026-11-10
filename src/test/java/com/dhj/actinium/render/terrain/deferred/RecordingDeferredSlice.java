package com.dhj.actinium.render.terrain.deferred;

import com.dhj.actinium.world.cloned.DeferredSlice;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal {@link DeferredSlice} for the scheduler tests: it models only the part of a slice the
 * scheduler interacts with, which is snapshot ownership. A slice handed to a mesh owns one
 * snapshot; {@link #releaseContext()} gives it back exactly once and later calls are no-ops, the
 * same way a real {@code WorldSlice} clears its cache reference before releasing the sections.
 *
 * <p>The block access methods are neutral stubs: the scheduler never reads blocks, and the test
 * handlers here never render, so no state has to be reproduced behind them.</p>
 */
final class RecordingDeferredSlice implements DeferredSlice {
    private final AtomicInteger releaseCount = new AtomicInteger();
    private boolean ownsSnapshot;
    private RuntimeException releaseFailure;

    RecordingDeferredSlice attachSnapshot() {
        this.ownsSnapshot = true;
        return this;
    }

    RecordingDeferredSlice failOnRelease(RuntimeException failure) {
        this.releaseFailure = failure;
        return this;
    }

    int releaseCount() {
        return this.releaseCount.get();
    }

    @Override
    public void releaseContext() {
        if (!this.ownsSnapshot) {
            return;
        }

        this.ownsSnapshot = false;
        this.releaseCount.incrementAndGet();

        if (this.releaseFailure != null) {
            throw this.releaseFailure;
        }
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return null;
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return false;
    }

    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return 0;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return 0;
    }

    @Override
    public WorldType getWorldType() {
        return null;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return false;
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return null;
    }

    @Override
    public int getBlockTint(BlockPos pos, BiomeColorHelper.ColorResolver resolver) {
        return 0;
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        return FluidState.EMPTY;
    }

    @Override
    public World getWorld() {
        return null;
    }
}
