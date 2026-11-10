package com.dhj.actinium.render;

import net.minecraft.world.biome.Biome;

/**
 * World reads a {@link WeatherColumns} cache performs for one precipitation column.
 *
 * <p>Vanilla weather builds its geometry from three world queries per column of every frame: the
 * column biome, the height precipitation lands on, and the combined light of the block it draws.
 * The cache repeats those reads on a rolling schedule instead, and this interface is the seam that
 * keeps that schedule independent of a live world. Implementations only forward to a world, which
 * lets a test drive the cache with fixed biomes and observe exactly which columns are resampled.</p>
 *
 * <p>{@link WeatherColumns} uses the sampler instance as the identity of the world it reads, so a
 * renderer must keep one sampler per world and pass the same instance on every update; a new
 * instance discards every cached column.</p>
 */
public interface WeatherSampler {

    /**
     * Returns the biome that covers a precipitation column.
     *
     * <p>Vanilla resolves the column biome at the column base ({@code y = 0}). The biome decides
     * whether the column precipitates at all, whether it can produce lightning, and the base
     * temperature the cache extrapolates along the column.</p>
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return the biome covering that column
     */
    Biome biome(int x, int z);

    /**
     * Returns the y coordinate of the block precipitation lands on.
     *
     * <p>The cache asks for this only after the biome reported precipitation, and the renderer uses
     * it as the bottom of the rain or snow quad.</p>
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return the precipitation height of that column
     */
    int precipitationHeight(int x, int z);

    /**
     * Returns the packed lightmap coordinates of a block.
     *
     * <p>The cache stores one value per column and queries again when the requested height changes.</p>
     *
     * @param x block x
     * @param y block y
     * @param z block z
     * @return packed sky and block light in the layout {@code World#getCombinedLight} produces
     */
    int combinedLight(int x, int y, int z);
}
