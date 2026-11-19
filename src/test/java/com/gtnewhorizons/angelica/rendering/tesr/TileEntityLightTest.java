package com.gtnewhorizons.angelica.rendering.tesr;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import toni.sodiumdynamiclights.SodiumDynamicLights;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the values the tile entity light path returns through the production bridge: the caller's
 * light for a block without neighbour brightness, the same early return for an opaque block, and the
 * recomputed neighbour-brightness value.
 *
 * <p>Each case runs against a real fixture world, so the merge goes through the addon method the
 * renderer reaches at runtime. The cached value
 * {@code MixinTileEntityRendererDispatcherTesrLight} returns when no shader pack is in use is merged
 * through the same call; that branch stays covered by code review because the handler is a private
 * mixin method.</p>
 */
class TileEntityLightTest {

    private static final BlockPos POS = new BlockPos(3, 72, -8);

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @AfterEach
    void clearDynamicLightLevel() {
        SodiumDynamicLights.setDynamicLightLevel(0);
    }

    @Test
    void mergesTheDynamicLevelIntoTheCallersLightWithoutNeighbourBrightness() {
        SodiumDynamicLights.setDynamicLightLevel(12);
        final LightWorld world = new LightWorld(new LightBlock(false, 0, 0));

        assertEquals(15 << 20 | 12 << 4, packedLight(world, 15 << 20 | 3 << 4));
    }

    @Test
    void mergesTheDynamicLevelIntoTheCallersLightForOpaqueBlocks() {
        SodiumDynamicLights.setDynamicLightLevel(12);
        final LightWorld world = new LightWorld(new LightBlock(true, 255, 0));

        assertEquals(15 << 20 | 12 << 4, packedLight(world, 15 << 20 | 3 << 4));
    }

    @Test
    void mergesTheDynamicLevelIntoTheRecomputedLight() {
        SodiumDynamicLights.setDynamicLightLevel(12);
        final LightWorld world = new LightWorld(new LightBlock(true, 0, 6)).withLight(11, 2);

        assertEquals(11 << 20 | 12 << 4, packedLight(world, 0));
    }

    @Test
    void keepsTheRecomputedBlockLightAboveTheDynamicLevel() {
        SodiumDynamicLights.setDynamicLightLevel(4);
        final LightWorld world = new LightWorld(new LightBlock(true, 0, 6)).withLight(11, 9);

        assertEquals(11 << 20 | 9 << 4, packedLight(world, 0));
    }

    private static int packedLight(LightWorld world, int callerLight) {
        return TileEntityLight.packedLight(world, POS.getX(), POS.getY(), POS.getZ(), callerLight);
    }

    /** Block state data the light path reads: neighbour brightness, opacity and emitted light. */
    private static final class LightBlock extends Block {

        private final boolean neighbourBrightness;
        private final int lightOpacity;
        private final int lightValue;

        private LightBlock(boolean neighbourBrightness, int lightOpacity, int lightValue) {
            super(Material.ROCK);
            this.neighbourBrightness = neighbourBrightness;
            this.lightOpacity = lightOpacity;
            this.lightValue = lightValue;
        }

        @Override
        public boolean getUseNeighborBrightness(IBlockState state) {
            return neighbourBrightness;
        }

        @Override
        public int getLightOpacity(IBlockState state) {
            return lightOpacity;
        }

        @Override
        public int getLightValue(IBlockState state) {
            return lightValue;
        }
    }

    /** Fixture world that answers the two light lookups the path makes with fixed values. */
    private static final class LightWorld extends World {

        private final IBlockState state;
        private int skyLight;
        private int blockLight;

        private LightWorld(Block block) {
            super(
                new SaveHandlerMP(),
                new WorldInfo(
                    new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                    "TileEntityLightTest"
                ),
                new SkyLightProvider(),
                new Profiler(),
                false
            );
            this.state = block.getDefaultState();
            this.provider.setWorld(this);
        }

        private LightWorld withLight(int sky, int block) {
            this.skyLight = sky;
            this.blockLight = block;
            return this;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public int getLightFor(EnumSkyBlock type, BlockPos pos) {
            return type == EnumSkyBlock.SKY ? skyLight : blockLight;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return true;
        }
    }

    private static final class SkyLightProvider extends WorldProvider {

        @Override
        public IChunkGenerator createChunkGenerator() {
            return null;
        }

        @Override
        public DimensionType getDimensionType() {
            return DimensionType.OVERWORLD;
        }
    }
}
