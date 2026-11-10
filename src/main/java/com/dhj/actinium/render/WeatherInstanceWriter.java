package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.glsm.ffp.WeatherInstancedAttribs;

import java.nio.ByteBuffer;

/**
 * Turns the cached precipitation columns into the instance data the instanced weather path uploads,
 * and owns the column selection the vanilla fallback shares.
 *
 * <p>One instance is a 48 byte record in three {@code vec4} attributes: {@code columnSpan} holds the
 * column offset from the camera window centre plus its bottom and top, {@code jitter} holds the two
 * uniforms and two gaussians vanilla draws for the texture scroll and the snow drift, and
 * {@code params} holds the packed lightmap, the low bits of the rain scroll hash and the rain flag.
 * Rain and snow are written as two segments so a single buffer can be drawn with two textures.</p>
 *
 * <p>Whether a column becomes rain or snow is the vanilla rule: the temperature at the column bottom,
 * adjusted by the biome provider for the precipitation height, compared against a fixed threshold.
 * The rule is injected so the writer can run without a world and so a test can observe the value the
 * decision was made on.</p>
 */
public final class WeatherInstanceWriter {

    /** Byte offset of the {@code vec4 columnSpan} attribute, from the shared glsm layout. */
    public static final int OFFSET_COLUMN_SPAN = WeatherInstancedAttribs.OFFSET_COLUMN_SPAN;

    /** Byte offset of the {@code vec4 jitter} attribute, from the shared glsm layout. */
    public static final int OFFSET_JITTER = WeatherInstancedAttribs.OFFSET_JITTER;

    /** Byte offset of the {@code vec4 params} attribute, from the shared glsm layout. */
    public static final int OFFSET_PARAMS = WeatherInstancedAttribs.OFFSET_PARAMS;

    /** Byte distance between two instances, from the shared glsm layout. */
    public static final int STRIDE = WeatherInstancedAttribs.STRIDE;

    /** Number of random values cached per column: two uniforms and two gaussians. */
    private static final int JITTER_COMPONENTS = 4;

    /** Vanilla threshold on the height adjusted temperature: at or above it the column rains. */
    private static final float RAIN_TEMPERATURE = 0.15F;

    /** Packed light of a fully lit block, used to lerp the snow lightmap towards daylight. */
    private static final int FULL_DAYLIGHT = 15728880;

    /**
     * Vanilla temperature adjustment for the precipitation height.
     *
     * <p>The column temperature is sampled at the column bottom, but vanilla decides rain against
     * snow with the temperature the biome provider reports for the height the precipitation lands
     * on. Dimensions and dimension mods override that adjustment, so it has to come from the world
     * rather than from a constant.</p>
     */
    @FunctionalInterface
    public interface TemperatureRule {

        /**
         * Returns the temperature used for the rain decision.
         *
         * @param temperature temperature sampled at the column bottom
         * @param height      precipitation height of the column
         * @return the height adjusted temperature
         */
        float atHeight(float temperature, int height);
    }

    /**
     * Receives every cached column of the window that needs weather geometry.
     *
     * <p>The vanilla fallback and the instance writer must draw the same columns, so the selection
     * lives here once and both consumers only decide how to emit a column.</p>
     */
    interface ColumnVisitor {

        /**
         * Accepts one drawable column.
         *
         * @param slot   ring slot of the column in the cache
         * @param x      block x of the column
         * @param z      block z of the column
         * @param bottom y the quad starts at, clamped to the window
         * @param top    y the quad ends at, clamped to the window
         * @param lightY y the column light is sampled at, which is not the quad bottom: vanilla
         *               samples the higher of the precipitation height and the camera block height
         * @param rain   whether the column rains instead of snowing
         */
        void column(int slot, int x, int z, int bottom, int top, int lightY, boolean rain);
    }

    private final WeatherColumns columns;
    private final TemperatureRule temperatureRule;

    /**
     * Creates a writer over one column cache.
     *
     * @param columns         cache holding the sampled columns
     * @param temperatureRule world rule that decides rain against snow
     */
    public WeatherInstanceWriter(WeatherColumns columns, TemperatureRule temperatureRule) {
        this.columns = columns;
        this.temperatureRule = temperatureRule;
    }

    /**
     * Writes the instance data of one precipitation type into the buffer.
     *
     * <p>The buffer is written at absolute offsets starting at {@code firstInstance}, so the caller
     * can append the snow segment after the rain segment without moving the buffer position. A
     * buffer too small for the written instances fails with an index out of bounds, which keeps a
     * sizing mistake from silently dropping weather.</p>
     *
     * @param target        direct buffer sized for the instance capacity
     * @param sampler       world reads for the column light
     * @param centerX       block x of the camera, centre of the window
     * @param centerY       block y of the camera, the window is clamped around it
     * @param centerZ       block z of the camera, centre of the window
     * @param cameraBlockY  interpolated camera block y, the height the column light is sampled at
     * @param radius        column radius of the window
     * @param wantRain      whether the rain segment or the snow segment is written
     * @param firstInstance index the first written instance goes to
     * @return the index after the last written instance, which is the rain instance count after the
     *         rain pass and the total instance count after the snow pass
     */
    public int write(ByteBuffer target, WeatherSampler sampler, int centerX, int centerY, int centerZ,
                     int cameraBlockY, int radius, boolean wantRain, int firstInstance) {
        final ColumnWriter writer = new ColumnWriter(target, sampler, centerX, centerZ, wantRain, firstInstance);
        forEachColumn(centerX, centerY, centerZ, cameraBlockY, radius, writer);
        return writer.nextInstance;
    }

    /**
     * Visits every cached column of the window that needs a quad, in row major window order.
     *
     * @param centerX      block x of the camera, centre of the window
     * @param centerY      block y of the camera, the window is clamped around it
     * @param centerZ      block z of the camera, centre of the window
     * @param cameraBlockY interpolated camera block y, the height the column light is sampled at
     * @param radius       column radius of the window
     * @param visitor      receives the columns that survive the selection
     */
    void forEachColumn(int centerX, int centerY, int centerZ, int cameraBlockY, int radius,
                       ColumnVisitor visitor) {
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                if (!columns.covers(x, z)) continue;
                final int slot = columns.slotAt(x, z);
                if (!columns.precipitatesAtSlot(slot)) continue;

                final int precipitationHeight = columns.heightAtSlot(slot);
                final int bottom = Math.max(centerY - radius, precipitationHeight);
                final int top = Math.max(centerY + radius, precipitationHeight);
                if (bottom == top) continue;

                final int lightY = Math.max(precipitationHeight, cameraBlockY);
                final float temperature = temperatureRule.atHeight(
                        columns.temperatureAtSlot(slot, bottom), precipitationHeight);
                visitor.column(slot, x, z, bottom, top, lightY, temperature >= RAIN_TEMPERATURE);
            }
        }
    }

    /**
     * Writes the columns of one precipitation type into the instance buffer.
     */
    private final class ColumnWriter implements ColumnVisitor {

        private final ByteBuffer target;
        private final WeatherSampler sampler;
        private final int centerX;
        private final int centerZ;
        private final boolean wantRain;
        private int nextInstance;

        private ColumnWriter(ByteBuffer target, WeatherSampler sampler, int centerX, int centerZ,
                             boolean wantRain, int firstInstance) {
            this.target = target;
            this.sampler = sampler;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.wantRain = wantRain;
            this.nextInstance = firstInstance;
        }

        @Override
        public void column(int slot, int x, int z, int bottom, int top, int lightY, boolean rain) {
            if (rain != wantRain) return;

            final int base = nextInstance * STRIDE;
            final int combinedLight = columns.lightAtSlot(sampler, slot, x, z, lightY);
            final int brightness = rain ? combinedLight : (combinedLight * 3 + FULL_DAYLIGHT) / 4;

            target.putFloat(base + OFFSET_COLUMN_SPAN, x - centerX);
            target.putFloat(base + OFFSET_COLUMN_SPAN + 4, z - centerZ);
            target.putFloat(base + OFFSET_COLUMN_SPAN + 8, bottom);
            target.putFloat(base + OFFSET_COLUMN_SPAN + 12, top);
            for (int component = 0; component < JITTER_COMPONENTS; component++) {
                target.putFloat(base + OFFSET_JITTER + component * 4, columns.jitterAtSlot(slot, component));
            }
            target.putFloat(base + OFFSET_PARAMS, brightness & 0xFFFF);
            target.putFloat(base + OFFSET_PARAMS + 4, (brightness >> 16) & 0xFFFF);
            target.putFloat(base + OFFSET_PARAMS + 8, columns.hashAtSlot(slot) & 31);
            target.putFloat(base + OFFSET_PARAMS + 12, rain ? 0.0F : 1.0F);
            nextInstance++;
        }
    }
}
