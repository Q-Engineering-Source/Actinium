package com.dhj.actinium.world;

import net.minecraft.init.Biomes;
import net.minecraft.world.biome.Biome;

/**
 * Biome lookup over the chunk neighbourhood captured by a {@link WorldSlice}. Biome colours are not
 * only requested for the section being built: mods that blend biome colours walk the 3x3 chunk
 * neighbourhood (Better Biome Blend gathers the nine chunks around the queried one through
 * {@code IBlockAccess#getBiome}, issue #162), so a chunk build centred next to the origin chunk can
 * reach one chunk outside the captured neighbourhood.
 *
 * A query beyond the captured range is clamped onto the nearest captured position: that biome is a
 * far better approximation than the fixed plains fallback, and biome data is continuous across chunk
 * borders, so biome-blended colours stay sane at the snapshot edge. A slice holding no snapshot
 * resolves to plains.
 *
 * Not thread-safe by itself: {@link #beginSnapshot(int, int)} and {@link #clearSnapshot()} are called
 * by the owning slice's task thread, which is the only caller.
 */
final class BiomeLookup {

    private static final int CHUNK_BLOCK_LENGTH = 16;

    // The mask and shift used to split a snapshot-local block offset into its chunk and
    // within-chunk parts. Both are exact because CHUNK_BLOCK_LENGTH is a power of two.
    private static final int CHUNK_BLOCK_MASK = CHUNK_BLOCK_LENGTH - 1;
    private static final int CHUNK_BLOCK_SHIFT = Integer.bitCount(CHUNK_BLOCK_MASK);

    private final int chunkSpan;

    private final int blockSpan;

    // Per-chunk biome tables, indexed by chunkIndex. An entry stays null until its chunk is
    // published by the owning slice.
    private final Biome[][] chunks;

    // Block coordinates of the snapshot's minimum corner, valid while snapshotHeld is true.
    private int baseX;
    private int baseZ;

    // Whether the owning slice currently holds a snapshot. False before the first copyData and
    // after reset, in which case every query resolves to plains.
    private boolean snapshotHeld;

    /**
     * @param chunkSpan the number of chunks captured along each horizontal axis, which must be
     *                  positive; the owning slice derives it from its own section layout so the two
     *                  can never drift apart
     */
    BiomeLookup(int chunkSpan) {
        if (chunkSpan <= 0) {
            throw new IllegalArgumentException("chunkSpan must be positive, got " + chunkSpan);
        }

        this.chunkSpan = chunkSpan;
        this.blockSpan = chunkSpan * CHUNK_BLOCK_LENGTH;
        this.chunks = new Biome[chunkSpan * chunkSpan][];
    }

    /**
     * Publishes the biome table of one captured chunk.
     *
     * @param chunkX the chunk's snapshot-local x index, from 0 to {@code chunkSpan - 1}
     * @param chunkZ the chunk's snapshot-local z index, from 0 to {@code chunkSpan - 1}
     * @param biomeData the chunk's 16x16 biome table, indexed by {@code (z << 4) | x}
     */
    void setChunk(int chunkX, int chunkZ, Biome[] biomeData) {
        this.chunks[chunkIndex(chunkX, chunkZ)] = biomeData;
    }

    /**
     * Marks the snapshot as held and records the block coordinates it starts at. The chunk tables
     * themselves are published through {@link #setChunk(int, int, Biome[])}.
     */
    void beginSnapshot(int baseX, int baseZ) {
        this.baseX = baseX;
        this.baseZ = baseZ;
        this.snapshotHeld = true;
    }

    /** Marks the snapshot as released; later queries resolve to plains until the next begin. */
    void clearSnapshot() {
        this.snapshotHeld = false;
    }

    /**
     * Resolves the biome at a world position. Positions outside the captured range are clamped onto
     * its nearest edge, so this method is total: it never throws and never reads outside the
     * captured tables.
     *
     * @return the biome at the position, or {@link Biomes#PLAINS} when the snapshot holds no data
     *         for it
     */
    Biome getBiome(int blockX, int blockZ) {
        if (!this.snapshotHeld) {
            return Biomes.PLAINS;
        }

        // Subtracting as long keeps extreme coordinates (Integer.MIN_VALUE/MAX_VALUE) from
        // wrapping around into a bogus in-range offset.
        int localX = clampOffset((long) blockX - this.baseX);
        int localZ = clampOffset((long) blockZ - this.baseZ);

        Biome[] chunk = this.chunks[chunkIndex(localX >> CHUNK_BLOCK_SHIFT, localZ >> CHUNK_BLOCK_SHIFT)];

        if (chunk == null) {
            return Biomes.PLAINS;
        }

        return chunk[((localZ & CHUNK_BLOCK_MASK) << CHUNK_BLOCK_SHIFT) | (localX & CHUNK_BLOCK_MASK)];
    }

    /** The table slot of a snapshot-local chunk index; only valid for in-range indices. */
    private int chunkIndex(int chunkX, int chunkZ) {
        return chunkZ * this.chunkSpan + chunkX;
    }

    private int clampOffset(long offset) {
        if (offset <= 0) {
            return 0;
        }

        return offset >= this.blockSpan ? this.blockSpan - 1 : (int) offset;
    }
}
