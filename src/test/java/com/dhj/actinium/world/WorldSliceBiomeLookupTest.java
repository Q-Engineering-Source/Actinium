package com.dhj.actinium.world;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Biomes;
import net.minecraft.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the biome lookup of {@link WorldSlice} against the failure reported in issue #162.
 *
 * <p>Better Biome Blend builds a whole chunk's blended colour table and gathers the biome of the
 * nine chunks around that chunk. While the mesher is building the chunk next to a slice's origin
 * chunk, that gather reaches one chunk outside the snapshot: the queried chunk was
 * {@code origin - 2}, the raw relative index was negative, and the chunk table was indexed with it
 * ({@code ArrayIndexOutOfBoundsException: Index -4 out of bounds for length 64}).</p>
 *
 * <p>{@link BiomeLookup} is exercised directly because it owns the coordinate arithmetic; a real
 * {@link WorldSlice} cannot be constructed without a live {@code World}. The snapshot is laid out
 * exactly like {@code WorldSlice.copyData} publishes it (base block coordinates plus one biome
 * table per captured chunk), so the queried coordinates below match the crash report.</p>
 */
class WorldSliceBiomeLookupTest {

    private static final int CHUNK_SPAN = 3;
    private static final int CHUNK = 16;

    // The crashing build was centred on chunk (630, 620); the queried chunk (619, 620) sat one
    // chunk away from the slice's origin, so the base chunk of that snapshot is 619.
    private static final int BASE_X = 619 * CHUNK;
    private static final int BASE_Z = 619 * CHUNK;

    // One distinct biome per snapshot chunk, so a resolution cannot accidentally pass with the
    // wrong chunk. Built in @BeforeAll: Biomes' own static initializer needs the bootstrapped
    // registries, and this test class is initialized before that hook runs.
    private static Biome[] chunkBiomes;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        // Biomes.PLAINS and friends are built from the block registry, which must be bootstrapped
        // first (same pattern as WorldSliceBlockBoxContainsTest).
        Bootstrap.register();

        chunkBiomes = new Biome[] {
                Biomes.OCEAN, Biomes.PLAINS, Biomes.DESERT,
                Biomes.FOREST, Biomes.TAIGA, Biomes.JUNGLE,
                Biomes.SAVANNA, Biomes.MESA, Biomes.SWAMPLAND
        };
    }

    @Test
    void insideSnapshotResolvesChunkAndBlock() {
        BiomeLookup lookup = filledSnapshot();

        // Snapshot chunk (2, 1) is two chunks east and one chunk north of the base chunk.
        assertEquals(chunkBiome(2, 1), lookup.getBiome(BASE_X + 2 * CHUNK + 7, BASE_Z + 1 * CHUNK + 9));
        assertEquals(chunkBiome(0, 2), lookup.getBiome(BASE_X + 15, BASE_Z + 2 * CHUNK));
        assertEquals(chunkBiome(0, 0), lookup.getBiome(BASE_X, BASE_Z));
        assertEquals(chunkBiome(2, 2), lookup.getBiome(BASE_X + 3 * CHUNK - 1, BASE_Z + 3 * CHUNK - 1));
    }

    @Test
    void biomeTableIsIndexedByXAndZ() {
        BiomeLookup lookup = new BiomeLookup(CHUNK_SPAN);
        lookup.beginSnapshot(BASE_X, BASE_Z);

        Biome[] table = new Biome[CHUNK * CHUNK];
        Arrays.fill(table, Biomes.PLAINS);
        table[(5 << 4) | 3] = Biomes.MUSHROOM_ISLAND;
        lookup.setChunk(1, 1, table);

        // Only the (x = 3, z = 5) column of that chunk carries the sentinel, so a swapped index
        // order (z << 4 | x versus x << 4 | z) cannot pass.
        assertEquals(Biomes.MUSHROOM_ISLAND, lookup.getBiome(BASE_X + CHUNK + 3, BASE_Z + CHUNK + 5));
        assertEquals(Biomes.PLAINS, lookup.getBiome(BASE_X + CHUNK + 5, BASE_Z + CHUNK + 3));
    }

    @Test
    void queryBehindTheSnapshotClampsInsteadOfThrowing() {
        BiomeLookup lookup = filledSnapshot();

        // The exact failing shape: the queried chunk sits one chunk before the snapshot on z (or
        // on x), which the old lookup turned into the negative table indices -4 and -1.
        assertEquals(chunkBiome(0, 0), lookup.getBiome(BASE_X + 4, BASE_Z - 1));
        assertEquals(chunkBiome(0, 0), lookup.getBiome(BASE_X - 1, BASE_Z + 4));
        assertEquals(chunkBiome(0, 0), lookup.getBiome(BASE_X - CHUNK, BASE_Z - CHUNK));
    }

    @Test
    void queryBeyondTheSnapshotClampsToItsEdge() {
        BiomeLookup lookup = filledSnapshot();

        assertEquals(chunkBiome(2, 2), lookup.getBiome(BASE_X + CHUNK_SPAN * CHUNK + 100, BASE_Z + CHUNK_SPAN * CHUNK + 100));
        assertEquals(chunkBiome(2, 0), lookup.getBiome(BASE_X + CHUNK_SPAN * CHUNK, BASE_Z + 3));
    }

    @Test
    void extremeCoordinatesDoNotWrapAround() {
        BiomeLookup lookup = filledSnapshot();

        assertEquals(chunkBiome(0, 0), lookup.getBiome(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(chunkBiome(2, 2), lookup.getBiome(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void snapshotNotHeldResolvesToPlains() {
        BiomeLookup lookup = new BiomeLookup(CHUNK_SPAN);

        assertEquals(Biomes.PLAINS, lookup.getBiome(BASE_X, BASE_Z));
        assertEquals(Biomes.PLAINS, lookup.getBiome(BASE_X + 40, BASE_Z + 40));
    }

    @Test
    void releasedSnapshotResolvesToPlains() {
        BiomeLookup lookup = filledSnapshot();
        lookup.clearSnapshot();

        assertEquals(Biomes.PLAINS, lookup.getBiome(BASE_X + 1, BASE_Z + 1));
    }

    @Test
    void chunkWithoutBiomeDataResolvesToPlains() {
        BiomeLookup lookup = new BiomeLookup(CHUNK_SPAN);
        lookup.beginSnapshot(BASE_X, BASE_Z);

        assertEquals(Biomes.PLAINS, lookup.getBiome(BASE_X + CHUNK + 2, BASE_Z + CHUNK + 2));
    }

    @Test
    void nonPositiveChunkSpanIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeLookup(0));
        assertThrows(IllegalArgumentException.class, () -> new BiomeLookup(-1));
    }

    private static BiomeLookup filledSnapshot() {
        BiomeLookup lookup = new BiomeLookup(CHUNK_SPAN);
        lookup.beginSnapshot(BASE_X, BASE_Z);

        for (int chunkX = 0; chunkX < CHUNK_SPAN; chunkX++) {
            for (int chunkZ = 0; chunkZ < CHUNK_SPAN; chunkZ++) {
                Biome[] table = new Biome[CHUNK * CHUNK];
                Arrays.fill(table, chunkBiome(chunkX, chunkZ));
                lookup.setChunk(chunkX, chunkZ, table);
            }
        }

        return lookup;
    }

    private static Biome chunkBiome(int chunkX, int chunkZ) {
        return chunkBiomes[chunkZ * CHUNK_SPAN + chunkX];
    }
}
