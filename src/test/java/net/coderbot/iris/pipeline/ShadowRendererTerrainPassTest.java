package net.coderbot.iris.pipeline;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import net.coderbot.iris.celeritas.WorldRendererCompat;
import net.coderbot.iris.pipeline.ShadowRendererPhaseTest.FakeWorldRenderingPipeline;
import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowRendererTerrainPassTest {
    @Test
    void consolidatedCutoutLayersDrawSharedPassOnceWithActualPassStage() {
        TerrainRenderPass solid = pass("solid");
        TerrainRenderPass shared = pass("cutout_mipped");
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.SKY);
        FakeWorldRendererCompat renderer = new FakeWorldRendererCompat(pipeline,
            configuration(solid, shared, shared));

        ShadowRenderer.TerrainPhaseScope.runOpaque(pipeline, renderer, 0.0, 0.0, 0.0);

        assertEquals(List.of(solid, shared), renderer.drawnPasses);
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_SOLID, WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED),
            renderer.drawnPhases);
        assertEquals(WorldRenderingPhase.SKY, pipeline.getPhase());
    }

    @Test
    void independentCutoutLayersDrawSeparatePassesWithMatchingStages() {
        TerrainRenderPass solid = pass("solid");
        TerrainRenderPass cutoutMipped = pass("cutout_mipped");
        TerrainRenderPass cutout = pass("cutout");
        FakeWorldRenderingPipeline pipeline = new FakeWorldRenderingPipeline(WorldRenderingPhase.SKY);
        FakeWorldRendererCompat renderer = new FakeWorldRendererCompat(pipeline,
            configuration(solid, cutoutMipped, cutout));

        ShadowRenderer.TerrainPhaseScope.runOpaque(pipeline, renderer, 0.0, 0.0, 0.0);

        assertEquals(List.of(solid, cutoutMipped, cutout), renderer.drawnPasses);
        assertEquals(List.of(WorldRenderingPhase.TERRAIN_SOLID, WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED,
            WorldRenderingPhase.TERRAIN_CUTOUT), renderer.drawnPhases);
        assertEquals(WorldRenderingPhase.SKY, pipeline.getPhase());
    }

    private static RenderPassConfiguration<BlockRenderLayer> configuration(
            TerrainRenderPass solid, TerrainRenderPass cutoutMipped, TerrainRenderPass cutout) {
        Map<BlockRenderLayer, Collection<TerrainRenderPass>> stages = new LinkedHashMap<>();
        stages.put(BlockRenderLayer.SOLID, List.of(solid));
        stages.put(BlockRenderLayer.CUTOUT_MIPPED, List.of(cutoutMipped));
        stages.put(BlockRenderLayer.CUTOUT, List.of(cutout));

        Material solidMaterial = material(solid);
        return new RenderPassConfiguration<>(Map.of(), stages, solidMaterial,
            material(cutoutMipped), material(cutoutMipped));
    }

    private static TerrainRenderPass pass(String name) {
        return TerrainRenderPass.builder()
            .name(name)
            .pipelineState(new TerrainRenderPass.PipelineState() {
                @Override
                public void setup() {
                }

                @Override
                public void clear() {
                }
            })
            .primitiveType(QuadPrimitiveType.TRIANGULATED)
            .vertexType(ChunkMeshFormats.COMPACT)
            .build();
    }

    private static Material material(TerrainRenderPass pass) {
        return new Material(pass, AlphaCutoffParameter.ZERO, true);
    }

    private static final class FakeWorldRendererCompat implements WorldRendererCompat {
        private final FakeWorldRenderingPipeline pipeline;
        private final RenderPassConfiguration<BlockRenderLayer> configuration;
        private final List<TerrainRenderPass> drawnPasses = new ArrayList<>();
        private final List<WorldRenderingPhase> drawnPhases = new ArrayList<>();

        private FakeWorldRendererCompat(FakeWorldRenderingPipeline pipeline,
                                        RenderPassConfiguration<BlockRenderLayer> configuration) {
            this.pipeline = pipeline;
            this.configuration = configuration;
        }

        @Override
        public void drawChunkLayer(BlockRenderLayer renderLayer, double x, double y, double z) {
            for (TerrainRenderPass pass : this.configuration.vanillaRenderStages().get(renderLayer)) {
                draw(pass);
            }
        }

        @Override
        public void drawChunkLayersDeduplicated(Collection<BlockRenderLayer> renderLayers, double x, double y, double z,
                                                Consumer<BlockRenderLayer> beforePass) {
            Map<TerrainRenderPass, BlockRenderLayer> passes = new LinkedHashMap<>();
            for (BlockRenderLayer layer : renderLayers) {
                for (TerrainRenderPass pass : this.configuration.vanillaRenderStages().get(layer)) {
                    passes.putIfAbsent(pass, layer);
                }
            }
            passes.forEach((pass, layer) -> {
                beforePass.accept(layer);
                draw(pass);
            });
        }

        private void draw(TerrainRenderPass pass) {
            this.drawnPasses.add(pass);
            this.drawnPhases.add(this.pipeline.getPhase());
        }

        @Override
        public int getVisibleChunkCount() {
            throw unsupported();
        }

        @Override
        public String getChunksDebugString() {
            throw unsupported();
        }

        @Override
        public void setCurrentViewport(Viewport viewport) {
            throw unsupported();
        }

        @Override
        public Viewport getLastViewport() {
            throw unsupported();
        }

        @Override
        public void markSectionGraphDirty() {
            throw unsupported();
        }

        @Override
        public void setupTerrain(Viewport viewport, SimpleWorldRenderer.CameraState cameraState, int frame,
                                 boolean spectator, boolean updateChunksImmediately) {
            throw unsupported();
        }

        @Override
        public void setupShadowTerrain(Viewport playerViewport, Viewport shadowViewport,
                                       SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator) {
            throw unsupported();
        }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Not used by terrain pass tests");
        }
    }
}
