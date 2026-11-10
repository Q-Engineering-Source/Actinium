package com.dhj.actinium.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.IRenderHandler;
import org.lwjgl.opengl.GL11;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure parts of the vanilla weather replacement.
 *
 * <p>The billboard axis, the transparency falloff, the particle attempt count and the column lightmap
 * are the parts of the fallback that touch neither the world nor the GL state, so they are verified
 * here against the vanilla formulas. Quad emission is checked through raw BufferBuilder data;
     * particle placement needs a live
 * client and are left to the in-game pass.</p>
 */
class WeatherRendererTest {

    /** Offset range of the vanilla billboard axis table: a 32 wide table centred on the camera. */
    private static final int AXIS_LIMIT = 16;

    /** Height the renderer is told to sample the light at, well above the column bottom. */
    private static final int LIGHT_Y = 96;

    /** Packed light the sampler reports, far enough from full daylight for the snow lerp to show. */
    private static final int LIGHT = 0x00A000A0;

    /** Packed light of a fully lit block, used to lerp the snow lightmap towards daylight. */
    private static final int FULL_DAYLIGHT = 15728880;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @Test
    void billboardAxisNeverProducesNaN() {
        for (int dz = -AXIS_LIMIT; dz < AXIS_LIMIT; dz++) {
            for (int dx = -AXIS_LIMIT; dx < AXIS_LIMIT; dx++) {
                float axisX = WeatherRenderer.billboardAxisX(dx, dz);
                float axisZ = WeatherRenderer.billboardAxisZ(dx, dz);
                assertFalse(Float.isNaN(axisX), "NaN axis x at " + dx + ", " + dz);
                assertFalse(Float.isNaN(axisZ), "NaN axis z at " + dx + ", " + dz);
                assertFalse(Float.isInfinite(axisX), "infinite axis x at " + dx + ", " + dz);
                assertFalse(Float.isInfinite(axisZ), "infinite axis z at " + dx + ", " + dz);
            }
        }
    }

    @Test
    void billboardAxisIsZeroForTheCameraColumn() {
        assertEquals(0.0F, WeatherRenderer.billboardAxisX(0, 0), 0.0F);
        assertEquals(0.0F, WeatherRenderer.billboardAxisZ(0, 0), 0.0F);
    }

    @Test
    void billboardAxisIsUnitLengthAwayFromTheCamera() {
        for (int dz = -AXIS_LIMIT; dz < AXIS_LIMIT; dz++) {
            for (int dx = -AXIS_LIMIT; dx < AXIS_LIMIT; dx++) {
                if (dx == 0 && dz == 0) continue;
                float axisX = WeatherRenderer.billboardAxisX(dx, dz);
                float axisZ = WeatherRenderer.billboardAxisZ(dx, dz);
                assertEquals(1.0F, axisX * axisX + axisZ * axisZ, 1.0e-5F, "axis length at " + dx + ", " + dz);
            }
        }
    }

    @Test
    void billboardAxisPointsAcrossTheViewRay() {
        // The quad faces the camera: its half axis is the offset rotated by a quarter turn.
        assertEquals(-1.0F, WeatherRenderer.billboardAxisX(0, 1), 1.0e-6F);
        assertEquals(0.0F, WeatherRenderer.billboardAxisZ(0, 1), 1.0e-6F);
        assertEquals(0.0F, WeatherRenderer.billboardAxisX(1, 0), 1.0e-6F);
        assertEquals(1.0F, WeatherRenderer.billboardAxisZ(1, 0), 1.0e-6F);
    }

    @Test
    void fallbackUsesVanillaHeightAndTextureVOrder() {
        final BufferBuilder buffer = new BufferBuilder(128);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        WeatherRenderer.emit(buffer, 0, 0, 0, 4, 0.5F, 0.0F, 0.0F, 1.0F, 2.0F, 6.0F, 0, 1.0F);
        buffer.finishDrawing();

        final int stride = buffer.getVertexFormat().getSize();
        final int vOffset = buffer.getVertexFormat().getOffset(1) + Float.BYTES;
        final float[] heights = {4.0F, 4.0F, 0.0F, 0.0F};
        final float[] textureVs = {2.0F, 2.0F, 6.0F, 6.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            assertEquals(heights[vertex], buffer.getByteBuffer().getFloat(vertex * stride + Float.BYTES));
            assertEquals(textureVs[vertex], buffer.getByteBuffer().getFloat(vertex * stride + vOffset));
        }
    }

    @Test
    void transparencyFallsTowardsTheWindowEdge() {
        assertEquals(1.0F, WeatherRenderer.fadeAlpha(0.0F, true, 1.0F), 1.0e-6F, "fully opaque below the camera");
        assertEquals(0.8F, WeatherRenderer.fadeAlpha(0.0F, false, 1.0F), 1.0e-6F,
                "vanilla snow starts below full opacity");
        assertEquals(0.5F, WeatherRenderer.fadeAlpha(1.0F, true, 1.0F), 1.0e-6F, "half strength at the window edge");
        assertEquals(0.25F, WeatherRenderer.fadeAlpha(1.0F, true, 0.5F), 1.0e-6F);
        assertTrue(WeatherRenderer.fadeAlpha(0.5F, true, 1.0F) > WeatherRenderer.fadeAlpha(1.0F, true, 1.0F),
                "rain fades with distance");
        assertTrue(WeatherRenderer.fadeAlpha(0.5F, true, 1.0F) > WeatherRenderer.fadeAlpha(0.5F, false, 1.0F),
                "rain fades faster than snow");
    }

    @Test
    void particleAttemptsFollowStrengthAndParticleSetting() {
        assertEquals(100, WeatherRenderer.particleAttempts(1.0F, 0));
        assertEquals(50, WeatherRenderer.particleAttempts(1.0F, 1));
        assertEquals(0, WeatherRenderer.particleAttempts(1.0F, 2));
        assertEquals(25, WeatherRenderer.particleAttempts(0.5F, 0));
        assertTrue(WeatherRenderer.particleAttempts(0.25F, 0) < WeatherRenderer.particleAttempts(0.5F, 0));
    }

    @Test
    void columnLightmapIsSampledAtTheReportedHeight() {
        LightRecordingSampler sampler = new LightRecordingSampler();
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, 1, 0);
        int slot = columns.slotAt(0, 0);

        int light = WeatherRenderer.columnLightmap(columns, sampler, slot, 0, 0, LIGHT_Y, true);

        assertEquals(LIGHT_Y, sampler.lastLightY, "the light is sampled at the height the selection reported");
        assertEquals(LIGHT, light, "rain uses the raw lightmap");

        WeatherRenderer.columnLightmap(columns, sampler, slot, 0, 0, LIGHT_Y, true);
        assertEquals(1, sampler.lightReads, "the column keeps the light of that height");
    }

    @Test
    void snowLightmapIsLerpedTowardsDaylight() {
        LightRecordingSampler sampler = new LightRecordingSampler();
        WeatherColumns columns = new WeatherColumns();
        columns.update(sampler, 0, 0, 1, 0);
        int slot = columns.slotAt(0, 0);

        int light = WeatherRenderer.columnLightmap(columns, sampler, slot, 0, 0, LIGHT_Y, false);

        assertEquals(LIGHT_Y, sampler.lastLightY);
        assertEquals((LIGHT * 3 + FULL_DAYLIGHT) / 4, light, "vanilla lerps snow three quarters towards daylight");
    }

    @Test
    void instancedPathNeedsTheConfigABackendThatDrawsInstancesAndAUsableVariant() {
        assertTrue(WeatherRenderer.useInstancedPath(true, true, false, false), "enabled without a shader pack");
        assertFalse(WeatherRenderer.useInstancedPath(false, true, false, false), "the config switch turns it off");
        assertFalse(WeatherRenderer.useInstancedPath(true, false, false, false),
                "a backend without instanced draws keeps the per-column fallback");
        assertFalse(WeatherRenderer.useInstancedPath(true, false, true, true),
                "a built variant does not make an unsupported backend draw instances");
        assertTrue(WeatherRenderer.useInstancedPath(true, true, true, true),
                "the shader pipeline uses its own instance variant once it is built");
        assertFalse(WeatherRenderer.useInstancedPath(true, true, true, false),
                "a pipeline without the weather variant keeps the per-column fallback");
    }

    @Test
    void rebuildIsForcedByRevisionCameraAndThePeriodicRefresh() {
        WeatherRenderer.BuildKey built = new WeatherRenderer.BuildKey(7, 100, 10, 70, -4, 70, 5);

        assertTrue(WeatherRenderer.needsRebuild(null, built), "nothing built yet");
        assertFalse(WeatherRenderer.needsRebuild(built, built), "the same frame does not rebuild");

        assertTrue(WeatherRenderer.needsRebuild(built, new WeatherRenderer.BuildKey(8, 100, 10, 70, -4, 70, 5)),
                "the cache resampled a column");
        assertTrue(WeatherRenderer.needsRebuild(built, new WeatherRenderer.BuildKey(7, 101, 11, 70, -4, 70, 5)),
                "the camera moved a block");
        assertTrue(WeatherRenderer.needsRebuild(built, new WeatherRenderer.BuildKey(7, 100, 10, 70, -4, 71, 5)),
                "the interpolated camera height moved the light sample");
        assertTrue(WeatherRenderer.needsRebuild(built, new WeatherRenderer.BuildKey(7, 100, 10, 70, -4, 70, 6)),
                "the window radius changed");

        int laterTick = built.updateCount() + 20;
        assertTrue(WeatherRenderer.needsRebuild(built, new WeatherRenderer.BuildKey(7, laterTick, 10, 70, -4, 70, 5)),
                "temperature and light only refresh on the periodic rebuild");
    }

    @Test
    void aForgeWeatherRendererKeepsItsOwnPass() {
        final WorldProviderSurface provider = new WorldProviderSurface();
        assertFalse(WeatherRenderer.hasCustomWeatherRenderer(provider));

        provider.setWeatherRenderer(new IRenderHandler() {
            @Override
            public void render(float partialTicks, WorldClient world, Minecraft mc) {
            }
        });

        assertTrue(WeatherRenderer.hasCustomWeatherRenderer(provider));
    }

    /**
     * Sampler that reports one fixed light and records the height it was asked for.
     */
    private static final class LightRecordingSampler implements WeatherSampler {

        private int lightReads;
        private int lastLightY = Integer.MIN_VALUE;

        @Override
        public Biome biome(int x, int z) {
            return Biomes.PLAINS;
        }

        @Override
        public int precipitationHeight(int x, int z) {
            return 0;
        }

        @Override
        public int combinedLight(int x, int y, int z) {
            lightReads++;
            lastLightY = y;
            return LIGHT;
        }
    }
}
