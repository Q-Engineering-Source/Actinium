package com.dhj.actinium.render;

import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the per-column rain and snow cache.
 *
 * <p>The cache is driven through {@link WeatherSampler} instead of a live {@code WorldClient}, so the
 * tests below count every world read the cache performs and observe exactly which columns are
 * resampled. That counting is the point of most cases: the cache exists to avoid repeating world
 * reads every frame, and the refresh rate, the ring slot arithmetic and the {@code revision} counter
 * are all only observable through the read pattern. Biomes are real vanilla instances, so the
 * temperature model is compared against {@link Biome#getTemperature(BlockPos)} itself.</p>
 */
class WeatherColumnsTest {

    private static final int RADIUS = 2;

    /** A radius R window is {@code 2R+1} columns wide, so the ring dimension stays odd. */
    private static final int SIZE = RADIUS * 2 + 1;

    private static final int GROUND_HEIGHT = 70;

    @BeforeAll
    static void bootstrapRegistries() {
        // Biomes.PLAINS and friends are built from the block registry, so the registries have to be
        // bootstrapped before any biome is touched. No static field may reference a biome: this
        // class initializes before the hook runs.
        Bootstrap.register();
    }

    @Test
    void noColumnIsCoveredBeforeTheFirstUpdate() {
        WeatherColumns columns = new WeatherColumns();

        assertFalse(columns.covers(0, 0));
        assertEquals(0, columns.revision());
    }

    @Test
    void updateCoversTheSquareAroundTheCamera() {
        WeatherColumns columns = new WeatherColumns();
        columns.update(new TestSampler(Biomes.PLAINS, GROUND_HEIGHT), 4, -7, RADIUS, 0);

        Set<Integer> slots = new HashSet<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int x = 4 + dx;
                int z = -7 + dz;
                assertTrue(columns.covers(x, z), "not covered: " + x + ", " + z);
                slots.add(columns.slotAt(x, z));
            }
        }

        assertEquals(SIZE * SIZE, slots.size(), "two columns of the window share a slot");
        assertFalse(columns.covers(4 + RADIUS + 1, -7));
        assertFalse(columns.covers(4 - RADIUS - 1, -7));
        assertFalse(columns.covers(4, -7 + RADIUS + 1));
        assertFalse(columns.covers(4, -7 - RADIUS - 1));
    }

    @Test
    void columnOneRingApartSharesASlotButIsNotCovered() {
        WeatherColumns columns = new WeatherColumns();
        columns.update(new TestSampler(Biomes.PLAINS, GROUND_HEIGHT), 0, 0, RADIUS, 0);

        int slot = columns.slotAt(1, -2);
        assertTrue(columns.covers(1, -2));
        assertEquals(slot, columns.slotAt(1 + SIZE, -2));
        assertFalse(columns.covers(1 + SIZE, -2), "a column one ring away must not be reported as cached");
    }

    @Test
    void negativeCoordinatesWrapIntoTheRing() {
        WeatherColumns columns = new WeatherColumns();
        columns.update(new TestSampler(Biomes.PLAINS, GROUND_HEIGHT), -7, -13, RADIUS, 0);

        assertTrue(columns.covers(-8, -14));
        assertTrue(columns.covers(-9, -15));
        assertFalse(columns.covers(0, 0));
        assertEquals(GROUND_HEIGHT, columns.heightAt(-8, -14));

        int slot = columns.slotAt(-8, -14);
        assertEquals(slot, columns.slotAt(-8 + SIZE, -14));
        assertFalse(columns.covers(-8 + SIZE, -14));
    }

    @Test
    void repeatedUpdateReusesCachedColumns() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);

        assertEquals(SIZE * SIZE, sampler.biomeReads);
        int revision = columns.revision();

        columns.update(sampler, 0, 0, RADIUS, 0);

        assertEquals(SIZE * SIZE, sampler.biomeReads, "an unchanged camera must not resample a single column");
        assertEquals(revision, columns.revision());
    }

    @Test
    void movingTheCameraSamplesOnlyTheNewStrip() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);

        int biomeReads = sampler.biomeReads;
        int heightReads = sampler.heightReads;

        columns.update(sampler, 1, 0, RADIUS, 0);

        assertEquals(SIZE, sampler.biomeReads - biomeReads, "only the entering column of every row is new");
        assertEquals(SIZE, sampler.heightReads - heightReads);
        assertTrue(columns.covers(RADIUS + 1, 0));
        assertFalse(columns.covers(-RADIUS, 0));
    }

    @Test
    void eachTickRefreshesOneSliceOfTheRing() {
        int radius = 5;
        int slots = (radius * 2 + 1) * (radius * 2 + 1);
        int slice = Math.max(1, slots / 20);
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, radius, 0);

        int biomeReads = sampler.biomeReads;
        columns.update(sampler, 0, 0, radius, 1);
        assertEquals(slice, sampler.biomeReads - biomeReads, "one tick must refresh exactly one slice");

        int afterRoll = sampler.biomeReads;
        columns.update(sampler, 0, 0, radius, 1);
        assertEquals(afterRoll, sampler.biomeReads, "a repeated tick must not roll the slice again");
    }

    @Test
    void radiusChangeResamplesTheWholeRing() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);

        int biomeReads = sampler.biomeReads;
        columns.update(sampler, 0, 0, RADIUS + 1, 0);

        int wider = (RADIUS + 1) * 2 + 1;
        assertEquals(wider * wider, sampler.biomeReads - biomeReads);
        assertTrue(columns.covers(RADIUS + 1, RADIUS + 1));

        biomeReads = sampler.biomeReads;
        columns.update(sampler, 0, 0, 1, 0);

        assertEquals(9, sampler.biomeReads - biomeReads);
        assertFalse(columns.covers(0, RADIUS));
    }

    @Test
    void dryColumnsSkipHeightSampling() {
        TestSampler sampler = new TestSampler(Biomes.DESERT, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);

        assertFalse(columns.precipitates(0, 0));
        assertEquals(0, sampler.heightReads, "a column without precipitation has no height to sample");
    }

    @Test
    void snowyColumnsPrecipitateWithoutRain() {
        Biome snowy = Biomes.COLD_TAIGA;
        WeatherColumns columns = new WeatherColumns();
        columns.update(new TestSampler(snowy, GROUND_HEIGHT), 0, 0, RADIUS, 0);

        assertFalse(snowy.canRain());
        assertTrue(snowy.getEnableSnow());
        assertFalse(columns.strikesLightning(0, 0));
        assertTrue(columns.precipitates(0, 0));
        assertEquals(GROUND_HEIGHT, columns.heightAt(0, 0));
    }

    @Test
    void columnThatStartsPrecipitatingIsSampledAndBumpsRevision() {
        TestSampler sampler = new TestSampler(Biomes.DESERT, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);
        int revision = columns.revision();

        sampler.setBiome(1, -1, Biomes.PLAINS);
        refreshEverySlot(columns, sampler, 0, 0, RADIUS, 0);

        assertTrue(columns.precipitates(1, -1));
        assertEquals(GROUND_HEIGHT, columns.heightAt(1, -1));
        assertTrue(columns.revision() > revision, "a precipitation change must invalidate the instance data");
    }

    @Test
    void precipitationChangeAloneBumpsRevision() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);
        int revision = columns.revision();

        // The column stops precipitating: the cached height is kept, so only the precipitation flag
        // changes and only that flag may invalidate the instance data.
        sampler.setBiome(1, 1, Biomes.DESERT);
        refreshEverySlot(columns, sampler, 0, 0, RADIUS, 0);
        assertFalse(columns.precipitates(1, 1));
        assertEquals(revision + 1, columns.revision());

        int dryRevision = columns.revision();
        sampler.setBiome(1, 1, Biomes.PLAINS);
        refreshEverySlot(columns, sampler, 0, 0, RADIUS, 0);
        assertTrue(columns.precipitates(1, 1));
        assertEquals(dryRevision + 1, columns.revision(), "the height did not change, so nothing else may invalidate");
    }

    @Test
    void changedPrecipitationHeightBumpsRevisionOnRefresh() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);
        assertEquals(GROUND_HEIGHT, columns.heightAt(1, 1));
        int revision = columns.revision();

        sampler.setHeight(1, 1, GROUND_HEIGHT + 20);
        refreshEverySlot(columns, sampler, 0, 0, RADIUS, 0);

        assertEquals(GROUND_HEIGHT + 20, columns.heightAt(1, 1));
        assertEquals(revision + 1, columns.revision(), "only the changed column may invalidate");
    }

    @Test
    void refreshWithUnchangedDataKeepsRevisionStable() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);
        int revision = columns.revision();

        refreshEverySlot(columns, sampler, 0, 0, RADIUS, 0);

        assertEquals(revision, columns.revision(), "resampling identical data must not invalidate");
    }

    @Test
    void samplerReplacementDropsCachedColumns() {
        WeatherColumns columns = new WeatherColumns();
        TestSampler first = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        columns.update(first, 0, 0, RADIUS, 0);
        int revision = columns.revision();

        TestSampler second = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        columns.update(second, 0, 0, RADIUS, 0);

        assertEquals(SIZE * SIZE, second.biomeReads, "another world must be sampled from scratch");
        assertTrue(columns.revision() > revision);
    }

    @Test
    void temperatureFollowsTheVanillaHeightFalloff() {
        int x = 128;
        int z = -91;
        for (Biome biome : new Biome[] {Biomes.PLAINS, Biomes.COLD_TAIGA}) {
            WeatherColumns columns = new WeatherColumns();
            columns.update(new TestSampler(biome, GROUND_HEIGHT), x, z, RADIUS, 0);

            for (int y = 0; y <= 200; y++) {
                float expected = biome.getTemperature(new BlockPos(x, y, z));
                assertEquals(expected, columns.temperatureAt(x, z, y), 1.0e-5F,
                        biome.getBiomeName() + " at y=" + y);
            }
        }
    }

    @Test
    void lightIsSampledOncePerHeightAndReused() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);
        int slot = columns.slotAt(0, 0);

        int groundLight = columns.lightAtSlot(sampler, slot, 0, 0, GROUND_HEIGHT);
        assertEquals(TestSampler.light(0, GROUND_HEIGHT, 0), groundLight);
        assertEquals(1, sampler.lightReads);

        assertEquals(groundLight, columns.lightAtSlot(sampler, slot, 0, 0, GROUND_HEIGHT));
        assertEquals(1, sampler.lightReads, "the same height must reuse the cached light");

        int higherLight = columns.lightAtSlot(sampler, slot, 0, 0, GROUND_HEIGHT + 1);
        assertEquals(TestSampler.light(0, GROUND_HEIGHT + 1, 0), higherLight);
        assertNotEquals(groundLight, higherLight);
        assertEquals(2, sampler.lightReads);

        columns.lightAtSlot(sampler, slot, 0, 0, GROUND_HEIGHT);
        assertEquals(3, sampler.lightReads, "only one height is cached per column");
    }

    @Test
    void jitterStaysStableWhenTheColumnIsSampledInAnotherOrder() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND_HEIGHT);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, RADIUS, 0);
        float[] first = jitterOf(columns, 0, 0);
        int hash = columns.hashAt(0, 0);

        columns.update(sampler, 512, -512, RADIUS, 0);
        assertFalse(columns.covers(0, 0));

        columns.update(sampler, 0, 0, RADIUS, 0);

        assertEquals(hash, columns.hashAt(0, 0));
        for (int i = 0; i < 4; i++) {
            assertEquals(first[i], columns.jitterAt(0, 0, i), 0.0F, "jitter " + i);
        }
    }

    @Test
    void fixtureBiomesCoverTheThreePrecipitationKinds() {
        assertTrue(Biomes.PLAINS.canRain());
        assertFalse(Biomes.PLAINS.getEnableSnow());

        assertTrue(Biomes.COLD_TAIGA.getEnableSnow());
        assertFalse(Biomes.COLD_TAIGA.canRain());

        assertFalse(Biomes.DESERT.canRain());
        assertFalse(Biomes.DESERT.getEnableSnow());
    }

    private static float[] jitterOf(WeatherColumns columns, int x, int z) {
        float[] jitter = new float[4];
        for (int i = 0; i < jitter.length; i++) {
            jitter[i] = columns.jitterAt(x, z, i);
        }
        return jitter;
    }

    /**
     * Advances the cache by one full refresh cycle, so every column of the window is resampled once.
     */
    private static void refreshEverySlot(WeatherColumns columns, WeatherSampler sampler,
                                         int cameraX, int cameraZ, int radius, int lastTick) {
        int slots = (radius * 2 + 1) * (radius * 2 + 1);
        int slice = Math.max(1, slots / 20);
        int ticks = (slots + slice - 1) / slice;
        for (int i = 1; i <= ticks; i++) {
            columns.update(sampler, cameraX, cameraZ, radius, lastTick + i);
        }
    }

    /**
     * Deterministic sampler backed by real vanilla biomes and a per-column height override.
     */
    private static final class TestSampler implements WeatherSampler {

        private final Biome defaultBiome;
        private final int defaultHeight;
        private final Map<Long, Biome> biomes = new HashMap<>();
        private final Map<Long, Integer> heights = new HashMap<>();

        private int biomeReads;
        private int heightReads;
        private int lightReads;

        private TestSampler(Biome defaultBiome, int defaultHeight) {
            this.defaultBiome = defaultBiome;
            this.defaultHeight = defaultHeight;
        }

        private void setBiome(int x, int z, Biome biome) {
            biomes.put(key(x, z), biome);
        }

        private void setHeight(int x, int z, int height) {
            heights.put(key(x, z), height);
        }

        @Override
        public Biome biome(int x, int z) {
            biomeReads++;
            Biome biome = biomes.get(key(x, z));
            return biome != null ? biome : defaultBiome;
        }

        @Override
        public int precipitationHeight(int x, int z) {
            heightReads++;
            Integer height = heights.get(key(x, z));
            return height != null ? height : defaultHeight;
        }

        @Override
        public int combinedLight(int x, int y, int z) {
            lightReads++;
            return light(x, y, z);
        }

        private static int light(int x, int y, int z) {
            return (x * 32768 + y * 256 + z) & 0xFFFFFF;
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
