package com.dhj.actinium.render.terrain.compile;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the routing predicate the {@code BlockRendererDispatcher} hook evaluates before it hands a
 * block to the fast block renderer: the fast-render option, the chunk buffer and world types, and
 * the fallback flags the mesher writes onto the buffer. Rendered output stays out of scope.
 */
class ChunkBlockRendererRouterTest {
    private static final int BUFFER_SIZE = 64;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    private final PlainBlockAccess plainWorld = new PlainBlockAccess();
    private final ChunkWorld chunkWorld = new ChunkWorld();
    private final ChunkBufferBuilder chunkBuffer =
            new ChunkBufferBuilder(BUFFER_SIZE, null, BlockRenderLayer.SOLID);

    @Test
    void onlyChunkBuffersInActiniumWorldsUseTheFastPath() {
        withFastBlockRenderer(true, () -> {
            assertTrue(ChunkBlockRendererRouter.shouldUseFastRenderer(this.chunkWorld, this.chunkBuffer));
            assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(this.chunkWorld, new BufferBuilder(BUFFER_SIZE)));
            assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(this.plainWorld, this.chunkBuffer));
        });
    }

    @Test
    void fluidAndModFallbacksKeepTheOriginalDispatcherCall() {
        withFastBlockRenderer(true, () -> {
            this.chunkBuffer.setForceVanillaRendering(true);
            assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(this.chunkWorld, this.chunkBuffer));

            this.chunkBuffer.setForceVanillaRendering(false);
            assertTrue(ChunkBlockRendererRouter.shouldUseFastRenderer(this.chunkWorld, this.chunkBuffer));
        });
    }

    @Test
    void disabledFastRenderingKeepsTheOriginalDispatcherCall() {
        withFastBlockRenderer(false, () ->
                assertFalse(ChunkBlockRendererRouter.shouldUseFastRenderer(this.chunkWorld, this.chunkBuffer)));
    }

    private static void withFastBlockRenderer(boolean enabled, Runnable assertions) {
        boolean previous = ActiniumRuntime.options().performance.useFastBlockRenderer;
        ActiniumRuntime.options().performance.useFastBlockRenderer = enabled;
        try {
            assertions.run();
        } finally {
            ActiniumRuntime.options().performance.useFastBlockRenderer = previous;
        }
    }

    /**
     * World-side getters that no route under test depends on; every one of them returns the
     * neutral value a worker thread would observe outside the captured section.
     */
    private static class PlainBlockAccess implements IBlockAccess {
        @Override
        public TileEntity getTileEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            return 0;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.getDefaultState();
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            return true;
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return Biomes.PLAINS;
        }

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return 0;
        }

        @Override
        public WorldType getWorldType() {
            return WorldType.DEFAULT;
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
            return false;
        }
    }

    private static final class ChunkWorld extends PlainBlockAccess implements ActiniumBlockAccess {
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
}
