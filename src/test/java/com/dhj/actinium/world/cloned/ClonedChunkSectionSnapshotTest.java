package com.dhj.actinium.world.cloned;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that a cloned section owns its snapshot: the block states, light arrays, biome table and
 * tile entities are copied out of the live chunk, so later changes to the live section cannot leak
 * into a mesh that is still building, and a recycled section cannot hand data of a previous
 * position to the next use.
 *
 * <p>{@link World} is a real fixture world backed by a {@link Chunk} built through its public
 * constructor; only the biome lookup is bound to the test chunk layout. The copy helpers are
 * exercised directly because {@code ClonedChunkSection#init} additionally consults the
 * release-time mod presence flags, which cannot be initialized outside a Forge launch (see the
 * report for the blocked cache-level test).</p>
 */
class ClonedChunkSectionSnapshotTest {
    private static final int SECTION_Y = 2;
    private static final int BLOCK_LIGHT = 11;
    private static final int SKY_LIGHT = 7;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @Test
    void copyBlockStatesCreatesAnIndependentCopy() {
        ExtendedBlockStorage live = new ExtendedBlockStorage(SECTION_Y << 4, true);
        live.set(1, 2, 3, Blocks.STONE.getDefaultState());
        live.set(15, 15, 15, Blocks.DIRT.getDefaultState());

        ExtendedBlockStorage snapshot = new ExtendedBlockStorage(SECTION_Y << 4, true);
        ClonedChunkSection.copyBlockStates(live, snapshot);

        assertSame(Blocks.STONE.getDefaultState(), snapshot.get(1, 2, 3));
        assertSame(Blocks.DIRT.getDefaultState(), snapshot.get(15, 15, 15));

        live.set(1, 2, 3, Blocks.GOLD_BLOCK.getDefaultState());

        assertSame(Blocks.STONE.getDefaultState(), snapshot.get(1, 2, 3));
    }

    @Test
    void copyBlockStatesOverwritesEveryEntryOfAReusedTarget() {
        ExtendedBlockStorage live = new ExtendedBlockStorage(SECTION_Y << 4, true);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    live.set(x, y, z, Blocks.STONE.getDefaultState());
                }
            }
        }

        ExtendedBlockStorage reused = new ExtendedBlockStorage(SECTION_Y << 4, true);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    reused.set(x, y, z, Blocks.DIRT.getDefaultState());
                }
            }
        }

        ClonedChunkSection.copyBlockStates(live, reused);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertSame(Blocks.STONE.getDefaultState(), reused.get(x, y, z));
                }
            }
        }
    }

    @Test
    void copyLightDataClonesBothArrays() {
        ExtendedBlockStorage live = new ExtendedBlockStorage(SECTION_Y << 4, true);
        live.setBlockLight(3, 4, 5, BLOCK_LIGHT);
        live.setSkyLight(3, 4, 5, SKY_LIGHT);

        NibbleArray[] snapshot = new NibbleArray[EnumSkyBlock.values().length];
        ClonedChunkSection.copyLightData(live, snapshot);

        assertEquals(BLOCK_LIGHT, snapshot[EnumSkyBlock.BLOCK.ordinal()].get(3, 4, 5));
        assertEquals(SKY_LIGHT, snapshot[EnumSkyBlock.SKY.ordinal()].get(3, 4, 5));

        live.setBlockLight(3, 4, 5, 2);
        live.setSkyLight(3, 4, 5, 1);

        assertEquals(BLOCK_LIGHT, snapshot[EnumSkyBlock.BLOCK.ordinal()].get(3, 4, 5));
        assertEquals(SKY_LIGHT, snapshot[EnumSkyBlock.SKY.ordinal()].get(3, 4, 5));
    }

    @Test
    void copyLightDataDropsArraysTheLiveSectionDoesNotHave() {
        ExtendedBlockStorage live = new ExtendedBlockStorage(SECTION_Y << 4, false);
        live.setBlockLight(3, 4, 5, BLOCK_LIGHT);

        NibbleArray[] snapshot = new NibbleArray[EnumSkyBlock.values().length];
        snapshot[EnumSkyBlock.BLOCK.ordinal()] = new NibbleArray();
        snapshot[EnumSkyBlock.SKY.ordinal()] = new NibbleArray();

        ClonedChunkSection.copyLightData(live, snapshot);

        assertNotNull(snapshot[EnumSkyBlock.BLOCK.ordinal()]);
        assertEquals(BLOCK_LIGHT, snapshot[EnumSkyBlock.BLOCK.ordinal()].get(3, 4, 5));
        assertNull(snapshot[EnumSkyBlock.SKY.ordinal()]);
    }

    @Test
    void copyBiomeDataFillsEveryColumnOfTheChunk() {
        FixtureWorld world = new FixtureWorld();
        Biome[] snapshot = new Biome[256];
        Arrays.fill(snapshot, Biomes.DESERT);

        ClonedChunkSection.copyBiomeData(snapshot, world, 3, 4);

        Biome expected = world.getBiome(new BlockPos((3 << 4) + 5, 100, (4 << 4) + 9));
        assertEquals(Biomes.FOREST, expected);

        for (Biome biome : snapshot) {
            assertSame(expected, biome);
        }
    }

    @Test
    void copyBlockEntitiesKeepsOnlyTheEntitiesOfTheSection() {
        FixtureWorld world = new FixtureWorld();
        Chunk chunk = new Chunk(world, 0, 0);

        TileEntity inSection = new TileEntityChest();
        TileEntity otherSection = new TileEntityChest();
        chunk.getTileEntityMap().put(new BlockPos(1, (SECTION_Y << 4) + 2, 3), inSection);
        chunk.getTileEntityMap().put(new BlockPos(1, (0 << 4) + 2, 3), otherSection);

        Short2ObjectMap<TileEntity> snapshot = new Short2ObjectOpenHashMap<>();
        snapshot.put(ClonedChunkSection.packLocal(0, 0, 0), new TileEntityChest());

        ClonedChunkSection.copyBlockEntities(snapshot, chunk, new SectionPos(0, SECTION_Y, 0));

        assertEquals(1, snapshot.size());
        assertNull(snapshot.get(ClonedChunkSection.packLocal(0, 0, 0)));
        assertSame(inSection, snapshot.get(ClonedChunkSection.packLocal(1, 2, 3)));
    }

    private static final class FixtureWorld extends World {
        private FixtureWorld() {
            super(new SaveHandlerMP(),
                    new WorldInfo(new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT), "SnapshotTest"),
                    new FixtureProvider(), new Profiler(), false);
            this.provider.setWorld(this);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return true;
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return (pos.getX() >> 4) == 3 && (pos.getZ() >> 4) == 4 ? Biomes.FOREST : Biomes.PLAINS;
        }
    }

    private static final class FixtureProvider extends WorldProvider {
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
