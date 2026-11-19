package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Remembers the lightmap the tile entity dispatcher looks up per tile per frame.
 *
 * <p>The combined light only changes inside a client tick: placing or breaking a light source and
 * the day cycle both take effect on the next tick. The cache is therefore keyed by the world's
 * tick count and dropped as soon as that count advances, so it cannot outlive the light it
 * remembers.</p>
 */
public final class TesrLightCache {

    private static final int MISSING = Integer.MIN_VALUE;

    private static final Long2IntOpenHashMap CACHE = new Long2IntOpenHashMap();

    static {
        CACHE.defaultReturnValue(MISSING);
    }

    private static World samplerWorld;
    private static LightSampler sampler;
    private static long cachedTick = Long.MIN_VALUE;

    private TesrLightCache() {
    }

    /** Source of the combined light for one position, so the cache does not have to know the world. */
    @FunctionalInterface
    interface LightSampler {
        int combinedLight(int x, int y, int z);
    }

    /** Drops every cached value. */
    public static void clear() {
        CACHE.clear();
        samplerWorld = null;
        sampler = null;
        cachedTick = Long.MIN_VALUE;
    }

    public static int getLightBrightnessForSkyBlocks(World world, int x, int y, int z, int minBlockLight) {
        if (minBlockLight != 0 || !GLStateManager.isMainThread()) {
            return world.getCombinedLight(new BlockPos(x, y, z), minBlockLight);
        }
        if (world != samplerWorld) {
            samplerWorld = world;
            sampler = (px, py, pz) -> world.getCombinedLight(new BlockPos(px, py, pz), 0);
        }
        return lookup(sampler, x, y, z, world.getTotalWorldTime());
    }

    /**
     * Looks one position up for the given tick, reusing the value cached for the same tick.
     *
     * <p>The tick is explicit so the refresh boundary is testable without a live world.</p>
     */
    static int lookup(LightSampler sampler, int x, int y, int z, long tick) {
        if (tick != cachedTick) {
            CACHE.clear();
            cachedTick = tick;
        }

        final long key = key(x, y, z);
        int packed = CACHE.get(key);
        if (packed == MISSING) {
            packed = sampler.combinedLight(x, y, z);
            CACHE.put(key, packed);
        }
        return packed;
    }

    // 26 bits for x and z cover the world border, 8 bits for y; no two positions share a key
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 34) | ((long) (y & 0xFF) << 26) | (long) (z & 0x3FFFFFF);
    }
}
