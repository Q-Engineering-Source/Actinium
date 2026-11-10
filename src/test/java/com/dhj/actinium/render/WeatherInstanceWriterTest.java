package com.dhj.actinium.render;

import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import static com.dhj.actinium.render.WeatherInstanceWriter.OFFSET_COLUMN_SPAN;
import static com.dhj.actinium.render.WeatherInstanceWriter.OFFSET_JITTER;
import static com.dhj.actinium.render.WeatherInstanceWriter.OFFSET_PARAMS;
import static com.dhj.actinium.render.WeatherInstanceWriter.STRIDE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instance buffer layout and column selection of the weather instance writer.
 *
 * <p>Every assertion reads the real {@link ByteBuffer} the renderer uploads, so the offsets, the
 * relative column span, the rain segment before the snow segment and the skipped columns are checked
 * on the bytes that reach the GPU. The column cache is driven by a deterministic sampler, which keeps
 * the tests on real vanilla biomes without a live world.</p>
 */
class WeatherInstanceWriterTest {

    private static final int RADIUS = 1;
    private static final int CENTER_X = 0;
    private static final int CENTER_Y = 80;
    private static final int CENTER_Z = 0;

    /** Camera block height used when the camera sits well above the precipitation height. */
    private static final int CAMERA_Y_HIGH = CENTER_Y + 20;

    /**
     * Precipitation height of the sampled columns. It sits at the camera height, so the column keeps
     * its own bottom and the window only raises the top.
     */
    private static final int GROUND = CENTER_Y;

    private static final int WINDOW_COLUMNS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);

    /** Vanilla lerps the snow lightmap three quarters towards full daylight. */
    private static final int FULL_DAYLIGHT = 15728880;

    /** Vanilla threshold on the height adjusted temperature that decides rain against snow. */
    private static final float RAIN_THRESHOLD = 0.15F;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @Test
    void writesEveryColumnOfTheWindow() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int next = writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, true, 0);

        assertEquals(WINDOW_COLUMNS, next);
        for (int instance = 0; instance < WINDOW_COLUMNS; instance++) {
            int dx = instance % (RADIUS * 2 + 1) - RADIUS;
            int dz = instance / (RADIUS * 2 + 1) - RADIUS;
            int base = instance * STRIDE;
            assertEquals(dx, target.getFloat(base + OFFSET_COLUMN_SPAN), 0.0F, "span x of instance " + instance);
            assertEquals(dz, target.getFloat(base + OFFSET_COLUMN_SPAN + 4), 0.0F, "span z of instance " + instance);
        }
    }

    @Test
    void spansTheColumnBetweenItsHeightAndTheWindowTop() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y,
                RADIUS, true, 0);

        int base = centreInstance() * STRIDE + OFFSET_COLUMN_SPAN;
        assertEquals(GROUND, target.getFloat(base + 8), 0.0F, "bottom");
        assertEquals(CENTER_Y + RADIUS, target.getFloat(base + 12), 0.0F, "top");
    }

    @Test
    void clampsTheColumnToTheWindowBelowTheCamera() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, CENTER_Y - 20);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y,
                RADIUS, true, 0);

        int base = centreInstance() * STRIDE + OFFSET_COLUMN_SPAN;
        assertEquals(CENTER_Y - RADIUS, target.getFloat(base + 8), 0.0F, "bottom");
        assertEquals(CENTER_Y + RADIUS, target.getFloat(base + 12), 0.0F, "top");
    }

    @Test
    void skipsColumnsWithoutPrecipitation() {
        TestSampler sampler = new TestSampler(Biomes.DESERT, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int next = writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, true, 0);

        assertEquals(0, next);
        assertEquals(0, sampler.lightReads, "a column without precipitation must not be lit");
    }

    @Test
    void skipsColumnsWhosePrecipitationIsAboveTheWindow() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, CENTER_Y + RADIUS);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int next = writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, true, 0);

        assertEquals(0, next, "a zero length column carries no quad");
    }

    @Test
    void writesEachColumnIntoThePassOfItsPrecipitationType() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        sampler.setBiome(-RADIUS, 0, Biomes.COLD_TAIGA);
        WeatherColumns columns = updatedColumns(sampler);
        WeatherInstanceWriter writer = writer(columns, identityRule());
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int rainInstances = writer.write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS,
                true, 0);
        int instanceCount = writer.write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS,
                false, rainInstances);

        assertEquals(WINDOW_COLUMNS - 1, rainInstances, "the snowy column belongs to the snow pass");
        assertEquals(WINDOW_COLUMNS, instanceCount);
        for (int instance = 0; instance < instanceCount; instance++) {
            float snowFlag = target.getFloat(instance * STRIDE + OFFSET_PARAMS + 12);
            assertEquals(instance < rainInstances ? 0.0F : 1.0F, snowFlag, 0.0F, "snow flag of instance " + instance);
        }
    }

    @Test
    void snowColumnsAreTheOnlyOnesInTheSnowPass() {
        TestSampler sampler = new TestSampler(Biomes.COLD_TAIGA, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        WeatherInstanceWriter writer = writer(columns, identityRule());
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int rainInstances = writer.write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS,
                true, 0);
        int instanceCount = writer.write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS,
                false, rainInstances);

        assertEquals(0, rainInstances);
        assertEquals(WINDOW_COLUMNS, instanceCount);
    }

    @Test
    void consultsTheTemperatureRuleAtThePrecipitationHeight() {
        TestSampler sampler = new TestSampler(Biomes.DESERT, GROUND);
        sampler.setBiome(CENTER_X, CENTER_Z, Biomes.PLAINS);
        WeatherColumns columns = updatedColumns(sampler);
        RecordingRule rule = new RecordingRule(RAIN_THRESHOLD - 1.0F);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int rainInstances = writer(columns, rule).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CAMERA_Y_HIGH, RADIUS, true, 0);

        assertEquals(0, rainInstances, "a rule below the rain threshold turns every column into snow");
        assertEquals(1, rule.calls, "only the precipitating column is classified");
        int slot = columns.slotAt(CENTER_X, CENTER_Z);
        assertEquals(columns.heightAtSlot(slot), rule.height, "the rule decides at the precipitation height");
        assertEquals(columns.temperatureAtSlot(slot, GROUND), rule.temperature, 0.0F,
                "on the temperature at the column bottom, independent of the camera height");
    }

    @Test
    void warmColumnsStayInTheRainPassOnly() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        WeatherInstanceWriter writer = writer(columns, identityRule());

        int rainInstances = writer.write(buffer(WINDOW_COLUMNS), sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, true, 0);
        int instanceCount = writer.write(buffer(WINDOW_COLUMNS), sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, false, rainInstances);

        assertEquals(WINDOW_COLUMNS, rainInstances);
        assertEquals(WINDOW_COLUMNS, instanceCount);
    }

    @Test
    void temperaturesBelowTheVanillaThresholdSnow() {
        TestSampler sampler = new TestSampler(Biomes.COLD_BEACH, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        int slot = columns.slotAt(CENTER_X, CENTER_Z);
        float temperature = columns.temperatureAtSlot(slot, GROUND);
        assertTrue(temperature > 0.0F && temperature < RAIN_THRESHOLD,
                "the fixture must stay between a zero threshold and the vanilla one: " + temperature);

        int rainInstances = writer(columns, identityRule()).write(buffer(WINDOW_COLUMNS), sampler,
                CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS, true, 0);

        assertEquals(0, rainInstances, "a positive temperature below the threshold still snows");
    }

    @Test
    void lightIsSampledAtTheCameraWhileTheTemperatureStaysAtTheColumnBottom() {
        TestSampler sampler = new TestSampler(Biomes.DESERT, CENTER_Y - 20);
        sampler.setBiome(CENTER_X, CENTER_Z, Biomes.PLAINS);
        WeatherColumns columns = updatedColumns(sampler);
        RecordingRule rule = new RecordingRule(1.0F);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int next = writer(columns, rule).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CAMERA_Y_HIGH,
                RADIUS, true, 0);

        assertEquals(1, next);
        assertEquals(1, rule.calls);
        int bottom = CENTER_Y - RADIUS;
        int slot = columns.slotAt(CENTER_X, CENTER_Z);
        assertEquals(CENTER_Y - 20, rule.height, "the rain decision uses the precipitation height");
        assertEquals(columns.temperatureAtSlot(slot, bottom), rule.temperature, 0.0F,
                "the temperature stays at the column bottom");
        assertEquals(CAMERA_Y_HIGH, sampler.lastLightY,
                "the light is sampled at the camera height, not at the column bottom");
        assertEquals(bottom, target.getFloat(OFFSET_COLUMN_SPAN + 8), 0.0F, "the quad still starts at the bottom");
        assertEquals(CENTER_Y + RADIUS, target.getFloat(OFFSET_COLUMN_SPAN + 12), 0.0F, "top");
    }

    @Test
    void lightIsSampledAtTheCameraOneBlockAboveTheColumn() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, CENTER_Y - 1);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int next = writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, true, 0);

        assertEquals(WINDOW_COLUMNS, next);
        assertEquals(CENTER_Y, sampler.lastLightY, "a camera one block above the column is where the light comes from");
        assertEquals(CENTER_Y - 1, target.getFloat(centreInstance() * STRIDE + OFFSET_COLUMN_SPAN + 8), 0.0F);
    }

    @Test
    void forEachColumnReportsTheLightHeightSeparatelyFromTheColumnSpan() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, CENTER_Y - 20);
        WeatherColumns columns = updatedColumns(sampler);
        RecordingVisitor visitor = new RecordingVisitor();

        writer(columns, identityRule()).forEachColumn(CENTER_X, CENTER_Y, CENTER_Z, CAMERA_Y_HIGH, RADIUS, visitor);

        assertEquals(WINDOW_COLUMNS, visitor.count);
        assertFalse(visitor.mixed, "every column of the window keeps the same geometry");
        assertEquals(CENTER_Y - RADIUS, visitor.bottom);
        assertEquals(CENTER_Y + RADIUS, visitor.top);
        assertEquals(CAMERA_Y_HIGH, visitor.lightY);
    }

    @Test
    void snowUsesTheLerpedVanillaLightmap() {
        TestSampler sampler = new TestSampler(Biomes.COLD_TAIGA, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y,
                RADIUS, false, 0);

        int lerped = (TestSampler.light(CENTER_X, GROUND, CENTER_Z) * 3 + FULL_DAYLIGHT) / 4;
        int base = centreInstance() * STRIDE + OFFSET_PARAMS;
        assertEquals(lerped & 0xFFFF, (int) target.getFloat(base), "block light");
        assertEquals(lerped >> 16 & 0xFFFF, (int) target.getFloat(base + 4), "sky light");
    }

    @Test
    void rainUsesTheRawLightmap() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y,
                RADIUS, true, 0);

        int combined = TestSampler.light(CENTER_X, GROUND, CENTER_Z);
        int base = centreInstance() * STRIDE + OFFSET_PARAMS;
        assertEquals(combined & 0xFFFF, (int) target.getFloat(base));
        assertEquals(combined >> 16 & 0xFFFF, (int) target.getFloat(base + 4));
    }

    @Test
    void paramsCarryTheHashAndTheColumnJitter() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y,
                RADIUS, true, 0);

        int centre = centreInstance();
        int slot = columns.slotAt(CENTER_X, CENTER_Z);
        assertEquals(columns.hashAtSlot(slot) & 31, (int) target.getFloat(centre * STRIDE + OFFSET_PARAMS + 8));
        for (int component = 0; component < 4; component++) {
            assertEquals(columns.jitterAtSlot(slot, component),
                    target.getFloat(centre * STRIDE + OFFSET_JITTER + component * 4), 0.0F,
                    "jitter " + component);
        }
    }

    @Test
    void skipsColumnsTheCacheNoLongerHolds() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 100, 100, RADIUS, 0);
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int next = writer(columns, identityRule()).write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z,
                CENTER_Y, RADIUS, true, 0);

        assertEquals(0, next, "a stale ring slot must not contribute a column");
        assertFalse(columns.covers(CENTER_X, CENTER_Z));
    }

    @Test
    void readsTheLightOfEveryWrittenColumnOnce() {
        TestSampler sampler = new TestSampler(Biomes.PLAINS, GROUND);
        WeatherColumns columns = updatedColumns(sampler);
        WeatherInstanceWriter writer = writer(columns, identityRule());
        ByteBuffer target = buffer(WINDOW_COLUMNS);

        int rainInstances = writer.write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS,
                true, 0);
        int instanceCount = writer.write(target, sampler, CENTER_X, CENTER_Y, CENTER_Z, CENTER_Y, RADIUS,
                false, rainInstances);

        assertEquals(WINDOW_COLUMNS, rainInstances);
        assertEquals(WINDOW_COLUMNS, instanceCount);
        assertEquals(WINDOW_COLUMNS, sampler.lightReads, "every column is lit exactly once per rebuild");
    }

    private static WeatherColumns updatedColumns(TestSampler sampler) {
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, CENTER_X, CENTER_Z, RADIUS, 0);
        return columns;
    }

    private static WeatherInstanceWriter writer(WeatherColumns columns,
                                                WeatherInstanceWriter.TemperatureRule rule) {
        return new WeatherInstanceWriter(columns, rule);
    }

    private static WeatherInstanceWriter.TemperatureRule identityRule() {
        return (temperature, height) -> temperature;
    }

    private static ByteBuffer buffer(int instances) {
        return ByteBuffer.allocateDirect(instances * STRIDE).order(ByteOrder.nativeOrder());
    }

    /**
     * Index of the column at the centre of the window in the emitted order.
     */
    private static int centreInstance() {
        return RADIUS * (RADIUS * 2 + 1) + RADIUS;
    }

    /**
     * Records every column the selection hands to a consumer.
     */
    private static final class RecordingVisitor implements WeatherInstanceWriter.ColumnVisitor {

        private int count;
        private int bottom = Integer.MIN_VALUE;
        private int top = Integer.MIN_VALUE;
        private int lightY = Integer.MIN_VALUE;
        private boolean mixed;

        @Override
        public void column(int slot, int x, int z, int bottom, int top, int lightY, boolean rain) {
            if (count > 0 && (bottom != this.bottom || top != this.top || lightY != this.lightY)) {
                this.mixed = true;
            }
            this.bottom = bottom;
            this.top = top;
            this.lightY = lightY;
            count++;
        }
    }

    /**
     * Records the arguments the rain decision receives and answers with a fixed temperature.
     */
    private static final class RecordingRule implements WeatherInstanceWriter.TemperatureRule {

        private final float outcome;
        private float temperature;
        private int height;
        private int calls;

        private RecordingRule(float outcome) {
            this.outcome = outcome;
        }

        @Override
        public float atHeight(float temperature, int height) {
            this.temperature = temperature;
            this.height = height;
            this.calls++;
            return this.outcome;
        }
    }

    /**
     * Deterministic sampler over real vanilla biomes with a shared precipitation height.
     */
    private static final class TestSampler implements WeatherSampler {

        private final Biome defaultBiome;
        private final int height;
        private final Map<Long, Biome> biomes = new HashMap<>();

        private int lightReads;
        private int lastLightY = Integer.MIN_VALUE;

        private TestSampler(Biome defaultBiome, int height) {
            this.defaultBiome = defaultBiome;
            this.height = height;
        }

        private void setBiome(int x, int z, Biome biome) {
            biomes.put(key(x, z), biome);
        }

        @Override
        public Biome biome(int x, int z) {
            Biome biome = biomes.get(key(x, z));
            return biome != null ? biome : defaultBiome;
        }

        @Override
        public int precipitationHeight(int x, int z) {
            return height;
        }

        @Override
        public int combinedLight(int x, int y, int z) {
            lightReads++;
            lastLightY = y;
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
