package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.compat.mojang.Camera;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.coderbot.iris.celeritas.CeleritasTerrainPipeline;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.features.FeatureFlags;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.gbuffer_overrides.state.RenderTargetStateListener;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.shaderpack.CloudSetting;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import net.coderbot.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.client.renderer.EntityRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShadowRendererPhaseTest {
    @Test
    void runsOpaqueDrawWithSolidPhaseAndRestoresPreviousPhase() {
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.SKY);

        ShadowRenderer.TerrainPhaseScope.runOpaque(pipeline,
            () -> assertEquals(WorldRenderingPhase.TERRAIN_SOLID, pipeline.getPhase()));

        assertEquals(WorldRenderingPhase.SKY, pipeline.getPhase());
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_SOLID, WorldRenderingPhase.SKY), pipeline.phaseChanges);
    }

    @Test
    void runsTranslucentDrawWithTranslucentPhaseAndRestoresPreviousPhase() {
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.SKY);

        ShadowRenderer.TerrainPhaseScope.runTranslucent(pipeline,
            () -> assertEquals(WorldRenderingPhase.TERRAIN_TRANSLUCENT, pipeline.getPhase()));

        assertEquals(WorldRenderingPhase.SKY, pipeline.getPhase());
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_TRANSLUCENT, WorldRenderingPhase.SKY), pipeline.phaseChanges);
    }

    @Test
    void runsCutoutMippedDrawWithItsOwnPhaseAndRestoresPreviousPhase() {
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.SKY);

        ShadowRenderer.TerrainPhaseScope.runCutoutMipped(pipeline,
            () -> assertEquals(WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED, pipeline.getPhase()));

        assertEquals(WorldRenderingPhase.SKY, pipeline.getPhase());
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED, WorldRenderingPhase.SKY), pipeline.phaseChanges);
    }

    @Test
    void runsCutoutDrawWithItsOwnPhaseAndRestoresPreviousPhase() {
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.SKY);

        ShadowRenderer.TerrainPhaseScope.runCutout(pipeline,
            () -> assertEquals(WorldRenderingPhase.TERRAIN_CUTOUT, pipeline.getPhase()));

        assertEquals(WorldRenderingPhase.SKY, pipeline.getPhase());
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_CUTOUT, WorldRenderingPhase.SKY), pipeline.phaseChanges);
    }

    @Test
    void restoresPreviousPhaseWhenOpaqueDrawFails() {
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.CLOUDS);
        IllegalStateException failure = new IllegalStateException("terrain draw failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> ShadowRenderer.TerrainPhaseScope.runOpaque(pipeline, () -> {
                assertEquals(WorldRenderingPhase.TERRAIN_SOLID, pipeline.getPhase());
                throw failure;
            }));

        assertSame(failure, thrown);
        assertEquals(WorldRenderingPhase.CLOUDS, pipeline.getPhase());
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_SOLID, WorldRenderingPhase.CLOUDS), pipeline.phaseChanges);
    }

    @Test
    void restoresPreviousPhaseWhenTranslucentDrawFails() {
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.CLOUDS);
        IllegalStateException failure = new IllegalStateException("terrain draw failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> ShadowRenderer.TerrainPhaseScope.runTranslucent(pipeline, () -> {
                assertEquals(WorldRenderingPhase.TERRAIN_TRANSLUCENT, pipeline.getPhase());
                throw failure;
            }));

        assertSame(failure, thrown);
        assertEquals(WorldRenderingPhase.CLOUDS, pipeline.getPhase());
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_TRANSLUCENT, WorldRenderingPhase.CLOUDS), pipeline.phaseChanges);
    }

    static final class FakeWorldRenderingPipeline implements WorldRenderingPipeline {
        private final List<WorldRenderingPhase> phaseChanges = new ArrayList<>();
        private WorldRenderingPhase phase;

        FakeWorldRenderingPipeline(WorldRenderingPhase phase) {
            this.phase = phase;
        }

        @Override
        public WorldRenderingPhase getPhase() {
            return this.phase;
        }

        @Override
        public void setPhase(WorldRenderingPhase phase) {
            this.phase = phase;
            this.phaseChanges.add(phase);
        }

        @Override
        public void beginLevelRendering() {
            throw unsupported();
        }

        @Override
        public void renderPreSkyPrepare() {
            throw unsupported();
        }

        @Override
        public void renderShadows(EntityRenderer levelRenderer, Camera camera) {
            throw unsupported();
        }

        @Override
        public void addDebugText(List<String> messages) {
            throw unsupported();
        }

        @Override
        public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
            throw unsupported();
        }

        @Override
        public void setOverridePhase(WorldRenderingPhase phase) {
            throw unsupported();
        }

        @Override
        public void setInputs(InputAvailability availability) {
            throw unsupported();
        }

        @Override
        public void setSpecialCondition(SpecialCondition special) {
            throw unsupported();
        }

        @Override
        public RenderTargetStateListener getRenderTargetStateListener() {
            throw unsupported();
        }

        @Override
        public int getCurrentNormalTexture() {
            throw unsupported();
        }

        @Override
        public int getCurrentSpecularTexture() {
            throw unsupported();
        }

        @Override
        public void onBindTexture(int id) {
            throw unsupported();
        }

        @Override
        public void restoreActivePass() {
            throw unsupported();
        }

        @Override
        public void beginHand() {
            throw unsupported();
        }

        @Override
        public void beginTranslucents() {
            throw unsupported();
        }

        @Override
        public void finalizeLevelRendering() {
            throw unsupported();
        }

        @Override
        public void destroy() {
            throw unsupported();
        }

        @Override
        public CeleritasTerrainPipeline getCeleritasTerrainPipeline() {
            throw unsupported();
        }

        @Override
        public FrameUpdateNotifier getFrameUpdateNotifier() {
            throw unsupported();
        }

        @Override
        public DHCompat getDHCompat() {
            throw unsupported();
        }

        @Override
        public boolean shouldDisableVanillaEntityShadows() {
            throw unsupported();
        }

        @Override
        public boolean shouldDisableDirectionalShading() {
            throw unsupported();
        }

        @Override
        public CloudSetting getCloudSetting() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderUnderwaterOverlay() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderVignette() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderSun() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderMoon() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderStars() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderSkyDisc() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderWeather() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderWeatherParticles() {
            throw unsupported();
        }

        @Override
        public boolean shouldWriteRainAndSnowToDepthBuffer() {
            throw unsupported();
        }

        @Override
        public boolean shouldRenderParticlesBeforeDeferred() {
            throw unsupported();
        }

        @Override
        public boolean allowConcurrentCompute() {
            throw unsupported();
        }

        @Override
        public float getSunPathRotation() {
            throw unsupported();
        }

        @Override
        public boolean hasFeature(FeatureFlags flag) {
            throw unsupported();
        }

        @Override
        public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
            throw unsupported();
        }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Not used by phase scope tests");
        }
    }
}
