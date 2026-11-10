package com.dhj.actinium.render;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Random;

/**
 * Per-column rain and snow data, sampled once and reused instead of recomputed for every column of
 * every frame.
 *
 * <p>The cache holds a {@code (2 * radius + 1)^2} ring of columns around the camera. A column owns
 * the slot {@code floorMod(x, size) * size + floorMod(z, size)}, so a moving camera overwrites the
 * columns that left the window, and the {@code x}/{@code z} key stored next to each slot tells a
 * cached column from a stale one that merely shares the slot. Moving the camera samples only the
 * strip that entered the window; while the camera stays put, each tick resamples one slice of
 * {@code slots / 20} columns so precipitation that starts, stops or settles at another height is
 * picked up within a second without touching all columns per frame.</p>
 *
 * <p>{@link #revision()} reports when the cached data changed in a way the rendered geometry depends
 * on: a column entering the cache, a precipitation change, or a precipitation height change. Light
 * and temperature never bump it, so a renderer that rebuilds its instance buffer on revision alone
 * must also rebuild on a fixed period. All cache reads and updates belong to the render thread.</p>
 */
public final class WeatherColumns {

    /** Height falloff of the biome temperature per block above the reference height. */
    private static final float TEMPERATURE_PER_BLOCK = 0.05F / 30.0F;

    /** Height whose biome temperature the falloff is anchored to. */
    private static final int TEMPERATURE_REFERENCE_Y = 65;

    /** Height that keeps the flat biome temperature below the falloff. */
    private static final int TEMPERATURE_BELOW_REFERENCE_Y = 64;

    /** Number of ticks a full refresh cycle of the ring spans. */
    private static final int REFRESH_TICKS = 20;

    /** Number of random values stored per column: two uniforms and two gaussians. */
    private static final int JITTER_COMPONENTS = 4;

    private final Random random = new Random();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private int size;
    private int[] keyX = new int[0];
    private int[] keyZ = new int[0];
    private boolean[] present = new boolean[0];
    private boolean[] precipitates = new boolean[0];
    private boolean[] strikesLightning = new boolean[0];
    private int[] height = new int[0];
    private int[] hash = new int[0];
    private float[] temperatureAtReference = new float[0];
    private float[] temperatureBelowReference = new float[0];
    private int[] light = new int[0];
    private int[] lightY = new int[0];
    private float[] jitter = new float[0];

    private int refreshCursor;
    private int revision;
    private int lastTick = Integer.MIN_VALUE;
    private int originX = Integer.MIN_VALUE;
    private int originZ = Integer.MIN_VALUE;
    private WeakReference<WeatherSampler> lastSampler = new WeakReference<>(null);

    /**
     * Returns the counter that changes whenever the cached column data changed.
     *
     * @return a monotonically increasing revision of the cached data
     */
    public int revision() {
        return revision;
    }

    /**
     * Samples the ring of columns around the camera and rolls the refresh slice.
     *
     * <p>Call once per frame with the current camera position and the world tick. The sampler must be
     * the same instance for the lifetime of a world: a different instance is treated as a world
     * change and discards every cached column, because heights and biomes of the previous world no
     * longer describe the current one.</p>
     *
     * @param sampler       world reads for the cached columns
     * @param cameraBlockX  camera block x, the centre of the ring
     * @param cameraBlockZ  camera block z, the centre of the ring
     * @param radius        column radius, giving a ring of {@code 2 * radius + 1} columns per axis
     * @param tick          world tick, used to roll the refresh slice once per tick
     */
    public void update(WeatherSampler sampler, int cameraBlockX, int cameraBlockZ, int radius, int tick) {
        final int wanted = radius * 2 + 1;
        final boolean resized = wanted != size;
        if (resized) resize(wanted);

        final boolean samplerChanged = lastSampler.get() != sampler;
        if (samplerChanged) {
            lastSampler = new WeakReference<>(sampler);
            Arrays.fill(present, false);
            originX = Integer.MIN_VALUE;
            originZ = Integer.MIN_VALUE;
            revision++;
        }

        final int slots = size * size;
        if (tick != lastTick) {
            lastTick = tick;
            final int slice = Math.max(1, slots / REFRESH_TICKS);
            for (int i = 0; i < slice; i++) {
                if (++refreshCursor >= slots) refreshCursor = 0;
                if (present[refreshCursor]) sample(sampler, keyX[refreshCursor], keyZ[refreshCursor], refreshCursor);
            }
        }

        if (!resized && cameraBlockX == originX && cameraBlockZ == originZ) return;
        originX = cameraBlockX;
        originZ = cameraBlockZ;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                final int x = cameraBlockX + dx;
                final int z = cameraBlockZ + dz;
                final int slot = slotOf(x, z);
                if (present[slot] && keyX[slot] == x && keyZ[slot] == z) continue;
                sample(sampler, x, z, slot);
            }
        }
    }

    /**
     * Returns whether a column precipitates, that is whether it needs rain or snow geometry.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return whether the cached column precipitates
     */
    public boolean precipitates(int x, int z) {
        return precipitates[slotOf(x, z)];
    }

    /**
     * Returns the ring slot of a column, which is what the slot accessors below take.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return the slot the column occupies, whether or not it is cached
     */
    public int slotAt(int x, int z) {
        return slotOf(x, z);
    }

    /**
     * Returns whether the cached slot precipitates.
     *
     * @param slot ring slot from {@link #slotAt(int, int)}
     * @return whether the column in that slot precipitates
     */
    public boolean precipitatesAtSlot(int slot) {
        return precipitates[slot];
    }

    /**
     * Returns the precipitation height cached for a slot.
     *
     * @param slot ring slot from {@link #slotAt(int, int)}
     * @return the y precipitation lands on, or the previous column's value for a column that does
     *         not precipitate
     */
    public int heightAtSlot(int slot) {
        return height[slot];
    }

    /**
     * Returns the vanilla rain scroll hash cached for a slot.
     *
     * @param slot ring slot from {@link #slotAt(int, int)}
     * @return the {@code hash} term the rain texture scroll adds to the update count
     */
    public int hashAtSlot(int slot) {
        return hash[slot];
    }

    /**
     * Returns one random value cached for a slot.
     *
     * @param slot  ring slot from {@link #slotAt(int, int)}
     * @param index component index: {@code 0} and {@code 2} are uniforms for the rain scroll and the
     *              snow drift start, {@code 1} and {@code 3} are gaussians for the snow drift speed
     * @return the cached jitter component
     */
    public float jitterAtSlot(int slot, int index) {
        return jitter[slot * JITTER_COMPONENTS + index];
    }

    /**
     * Returns the biome temperature of a column at a height.
     *
     * <p>The cache stores the temperature at two heights and extrapolates between them, which is
     * exactly the vanilla height falloff of {@code Biome#getTemperature}: the base temperature below
     * the reference height, then {@code 0.05 / 30} per block above it.</p>
     *
     * @param slot ring slot from {@link #slotAt(int, int)}
     * @param y    block y
     * @return the biome temperature at that height
     */
    public float temperatureAtSlot(int slot, int y) {
        if (y <= TEMPERATURE_BELOW_REFERENCE_Y) return temperatureBelowReference[slot];
        return temperatureAtReference[slot] - (y - TEMPERATURE_REFERENCE_Y) * TEMPERATURE_PER_BLOCK;
    }

    /**
     * Returns the packed lightmap coordinates of a slot at a height, sampling at most once per height.
     *
     * @param sampler world reads for the column
     * @param slot    ring slot from {@link #slotAt(int, int)}
     * @param x       block x of the column
     * @param z       block z of the column
     * @param y       block y to light
     * @return packed sky and block light
     */
    public int lightAtSlot(WeatherSampler sampler, int slot, int x, int z, int y) {
        if (lightY[slot] != y) {
            lightY[slot] = y;
            light[slot] = sampler.combinedLight(x, y, z);
        }
        return light[slot];
    }

    /**
     * Returns whether a column is cached for its own coordinates.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return whether the slot holds that exact column
     */
    public boolean covers(int x, int z) {
        if (size == 0) return false;
        final int slot = slotOf(x, z);
        return present[slot] && keyX[slot] == x && keyZ[slot] == z;
    }

    /**
     * Returns whether the cached column can produce lightning.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return whether the column biome rains rather than snows
     */
    public boolean strikesLightning(int x, int z) {
        return strikesLightning[slotOf(x, z)];
    }

    /**
     * Returns the cached precipitation height of a column.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return the y precipitation lands on
     */
    public int heightAt(int x, int z) {
        return height[slotOf(x, z)];
    }

    /**
     * Returns the vanilla rain scroll hash of a column.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @return the {@code hash} term the rain texture scroll adds to the update count
     */
    public int hashAt(int x, int z) {
        return hash[slotOf(x, z)];
    }

    /**
     * Returns the biome temperature of a column at a height.
     *
     * @param x block x of the column
     * @param z block z of the column
     * @param y block y
     * @return the biome temperature at that height
     */
    public float temperatureAt(int x, int z, int y) {
        return temperatureAtSlot(slotOf(x, z), y);
    }

    /**
     * Returns the packed lightmap coordinates of a column at a height, sampling at most once per
     * height.
     *
     * @param sampler world reads for the column
     * @param x       block x of the column
     * @param z       block z of the column
     * @param y       block y to light
     * @return packed sky and block light
     */
    public int lightAt(WeatherSampler sampler, int x, int z, int y) {
        return lightAtSlot(sampler, slotOf(x, z), x, z, y);
    }

    /**
     * Returns one cached random value of a column.
     *
     * @param x     block x of the column
     * @param z     block z of the column
     * @param index component index, see {@link #jitterAtSlot(int, int)}
     * @return the cached jitter component
     */
    public float jitterAt(int x, int z, int index) {
        return jitterAtSlot(slotOf(x, z), index);
    }

    private int slotOf(int x, int z) {
        return Math.floorMod(x, size) * size + Math.floorMod(z, size);
    }

    private void resize(int wanted) {
        size = wanted;
        final int slots = wanted * wanted;
        keyX = new int[slots];
        keyZ = new int[slots];
        present = new boolean[slots];
        precipitates = new boolean[slots];
        strikesLightning = new boolean[slots];
        height = new int[slots];
        hash = new int[slots];
        temperatureAtReference = new float[slots];
        temperatureBelowReference = new float[slots];
        light = new int[slots];
        lightY = new int[slots];
        jitter = new float[slots * JITTER_COMPONENTS];
        refreshCursor = 0;
    }

    private void sample(WeatherSampler sampler, int x, int z, int slot) {
        final boolean sameColumn = present[slot] && keyX[slot] == x && keyZ[slot] == z;
        final boolean wasPrecipitating = precipitates[slot];
        final int wasHeight = height[slot];

        keyX[slot] = x;
        keyZ[slot] = z;
        present[slot] = true;
        lightY[slot] = Integer.MIN_VALUE;

        final Biome biome = sampler.biome(x, z);
        strikesLightning[slot] = biome.canRain();
        precipitates[slot] = strikesLightning[slot] || biome.getEnableSnow();
        if (!sameColumn || precipitates[slot] != wasPrecipitating) revision++;
        if (!precipitates[slot]) return;

        height[slot] = sampler.precipitationHeight(x, z);
        if (height[slot] != wasHeight) revision++;
        temperatureAtReference[slot] = biome.getTemperature(pos.setPos(x, TEMPERATURE_REFERENCE_Y, z));
        temperatureBelowReference[slot] = biome.getTemperature(pos.setPos(x, TEMPERATURE_BELOW_REFERENCE_Y, z));

        // Vanilla computes both terms in int and lets them overflow before widening the seed.
        hash[slot] = x * x * 3121 + x * 45238971 + z * z * 418711 + z * 13761;
        random.setSeed((long) ((x * x * 3121 + x * 45238971) ^ (z * z * 418711 + z * 13761)));

        final int base = slot * JITTER_COMPONENTS;
        jitter[base] = (float) random.nextDouble();
        jitter[base + 1] = (float) random.nextGaussian();
        jitter[base + 2] = (float) random.nextDouble();
        jitter[base + 3] = (float) random.nextGaussian();
    }
}
