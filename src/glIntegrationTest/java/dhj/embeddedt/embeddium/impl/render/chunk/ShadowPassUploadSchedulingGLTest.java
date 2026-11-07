package dhj.embeddedt.embeddium.impl.render.chunk;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.render.chunk.fog.FogService;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderListManager;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderComponent;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the two terrain passes of one frame through a real {@link RenderSectionManager} and a real
 * {@link SimpleWorldRenderer}: the shadow pass uploads the build results it waited on before it submits its
 * searches, and the terrain pass only uploads again when it waited on rebuilds the shadow pass could not have seen.
 *
 * <p>The section manager runs with no worker threads, so every job the manager submits is executed inline by
 * {@code awaitCompletion}/{@code tick} on the calling thread. That keeps the dispatches and uploads of one pass
 * deterministic while the scheduling decisions under test stay the production ones.</p>
 */
class ShadowPassUploadSchedulingGLTest extends GLStateManagerIntegrationFixture {
    private static final int RENDER_DISTANCE = 8;
    private static final int MAX_SECTION = 16;

    private static final Viewport PLAYER_VIEWPORT = viewport();
    private static final Viewport SHADOW_VIEWPORT = viewport();

    private static final RenderPassConfiguration<Object> CONFIGURATION = configuration();
    private static final FogService FOG_SERVICE = new TestFogService();

    private TestWorld world;
    private TestWorldRenderer renderer;

    @BeforeEach
    void setUp() {
        RenderDevice.INSTANCE.makeActive();
        this.world = new TestWorld();
        this.renderer = new TestWorldRenderer();
        this.renderer.setWorld(this.world);
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.renderer != null) {
                this.renderer.setWorld(null);
            }
        } finally {
            RenderDevice.INSTANCE.makeInactive();
        }
    }

    /**
     * The shadow pass renders its terrain before the player's pass, so a build result left in the queue would only
     * be uploaded after the shadow search has already read the section. Uploading first means the search sees it.
     */
    @Test
    void shadowPassUploadsBuildResultsBeforeSubmittingItsSearches() {
        RecordingRenderSectionManager manager = this.renderer.getRenderSectionManager();

        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(64.0f), 1, false);

        assertEquals(List.of("updateChunks", "upload", "updateForShadowPass"), manager.events,
                "the shadow pass schedules, uploads and only then searches");
    }

    /**
     * The rebuild the shadow pass dispatches lands in the queue during its own scheduling step; only its upload
     * applies the result, so without one the section stays unbuilt until the player's pass.
     */
    @Test
    void shadowPassAppliesTheBuildResultsItDispatched() {
        RecordingRenderSectionManager manager = this.renderer.getRenderSectionManager();
        manager.onSectionAdded(0, 4, 0);
        manager.scheduleRebuild(0, 4, 0, false);

        // The search of the first pass collects the rebuild into the shadow lists; the second pass dispatches it.
        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(64.0f), 1, false);
        assertFalse(manager.isSectionBuilt(0, 4, 0), "the queued rebuild is not built yet");

        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(64.0f), 2, false);

        assertTrue(manager.isSectionBuilt(0, 4, 0), "the shadow pass uploads the rebuild it dispatched");
    }

    /**
     * The shadow pass already drained the build queue in this frame, so a second drain in the terrain pass would only
     * join the searches the shadow pass submitted.
     */
    @Test
    void terrainPassDoesNotUploadAgainAfterTheShadowPass() {
        RecordingRenderSectionManager manager = this.renderer.getRenderSectionManager();

        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(64.0f), 1, false);
        this.renderer.setupTerrain(PLAYER_VIEWPORT, cameraState(64.0f), 1, false, false);

        assertEquals(List.of("updateChunks", "upload", "updateForShadowPass", "updateChunks", "update"), manager.events,
                "only the shadow pass of this frame uploads");
    }

    /**
     * The rebuilds the terrain pass waits on reach the queue only after the shadow pass uploaded, so skipping the
     * upload here would leave the sections unbuilt until the next frame.
     */
    @Test
    void terrainPassUploadsWhenItWaitedOnBlockingRebuilds() {
        RecordingRenderSectionManager manager = this.renderer.getRenderSectionManager();
        manager.onSectionAdded(0, 4, 0);

        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(64.0f), 1, false);
        manager.scheduleRebuild(0, 4, 0, false);

        this.renderer.setupTerrain(PLAYER_VIEWPORT, cameraState(64.0f), 1, false, true);

        assertEquals(List.of("updateChunks", "upload", "updateForShadowPass", "updateChunks", "upload", "update", "upload"),
                manager.events, "the terrain pass uploads the rebuild it waited on before it consumes the frame");
        assertTrue(manager.isSectionBuilt(0, 4, 0), "the rebuild the terrain pass waited on is applied");
        assertFalse(manager.hasBlockingResultsPending(), "the upload clears the pending blocking results");
    }

    /**
     * The default hook keeps the shadow pass searching every frame, which is what the unshaded path expects.
     */
    @Test
    void shadowSearchIsSubmittedByDefault() {
        RecordingRenderSectionManager manager = this.renderer.getRenderSectionManager();

        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(64.0f), 1, false);

        assertEquals(1, manager.shadowLists().getLastUpdatedFrame(), "the default hook submits the shadow search");
    }

    /**
     * A subclass that knows its viewport did not move gates the search off; the shadow lists must then keep the
     * lists of the previous pass while the terrain search of the same pass keeps running.
     */
    @Test
    void canSubmitShadowGraphSearchGatesTheShadowSearch() {
        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            GatedRenderSectionManager manager = new GatedRenderSectionManager(commandList);

            try {
                manager.updateForShadowPass(PLAYER_VIEWPORT, SHADOW_VIEWPORT, 1, false);
                manager.markGraphDirty();
                manager.updateForShadowPass(PLAYER_VIEWPORT, SHADOW_VIEWPORT, 2, false);

                assertEquals(2, manager.terrainLists().getLastUpdatedFrame(), "the terrain search still runs");
                assertEquals(0, manager.shadowLists().getLastUpdatedFrame(), "the gated shadow search is not submitted");
                assertTrue(manager.shadowLists().isNeedsUpdate(), "the gated shadow lists stay pending for the next pass");
            } finally {
                manager.destroy();
            }
        }
    }

    /**
     * The shadow pass renders with its own fog, so the camera state it was handed differs from the player pass's state
     * in the fog cutoff alone. The player pass must not read that as camera movement and dirty the graph the shadow
     * pass searched for this frame.
     */
    @Test
    void terrainPassReusesTheShadowPassCameraState() {
        RecordingRenderSectionManager manager = this.renderer.getRenderSectionManager();

        this.renderer.setupShadowTerrain(PLAYER_VIEWPORT, SHADOW_VIEWPORT, cameraState(8.0f), 1, false);
        assertFalse(manager.terrainLists().isNeedsUpdate(), "the shadow pass searched for this frame");

        this.renderer.setupTerrain(PLAYER_VIEWPORT, cameraState(64.0f), 1, false, false);

        assertFalse(manager.terrainLists().isNeedsUpdate(),
                "a fog-only camera difference must not dirty the graph the shadow pass already searched");
        assertFalse(manager.didShadowPassRunThisFrame(), "the terrain pass consumes the shadow pass of this frame");
    }

    private static Viewport viewport() {
        return new Viewport((minX, minY, minZ, maxX, maxY, maxZ) -> true, new Vector3d(0.5, 70.0, 0.5));
    }

    private static SimpleWorldRenderer.CameraState cameraState(float fogDistance) {
        return new SimpleWorldRenderer.CameraState(0.5, 70.0, 0.5, 0.0f, 0.0f, fogDistance);
    }

    private static RenderPassConfiguration<Object> configuration() {
        TerrainRenderPass solid = TerrainRenderPass.builder()
                .name("solid")
                .pipelineState(TerrainRenderPass.PipelineState.DEFAULT)
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(ChunkMeshFormats.COMPACT)
                .build();
        Material material = new Material(solid, AlphaCutoffParameter.ZERO, true);

        return new RenderPassConfiguration<>(Map.of(), Map.of(), material, material, material);
    }

    /**
     * Stands in for the game world: {@link SimpleWorldRenderer#setWorld} subscribes to the world's chunk tracker, and
     * nothing in these tests changes chunk load state.
     */
    private static final class TestWorld implements ChunkTrackerHolder {
        private final ChunkTracker tracker = new ChunkTracker();

        @Override
        public ChunkTracker sodium$getTracker() {
            return this.tracker;
        }
    }

    private static final class TestWorldRenderer
            extends SimpleWorldRenderer<TestWorld, RecordingRenderSectionManager, Object, Object, Object> {
        @Override
        public int getEffectiveRenderDistance() {
            return RENDER_DISTANCE;
        }

        @Override
        protected RecordingRenderSectionManager createRenderSectionManager(CommandList commandList) {
            return new RecordingRenderSectionManager(commandList);
        }

        @Override
        protected ChunkRenderMatrices createChunkRenderMatrices() {
            return new ChunkRenderMatrices(new Matrix4f(), new Matrix4f());
        }

        @Override
        public int getMinimumBuildHeight() {
            return 0;
        }

        @Override
        public int getMaximumBuildHeight() {
            return 256;
        }

        @Override
        protected void renderBlockEntityList(List<Object> list, Object context) {
        }

        /**
         * The host marks the shadow pass for the duration of {@code setupShadowTerrain}; the manager reads that flag
         * in {@code isInShadowPass()}, which selects the shadow lists for this pass's scheduling.
         */
        @Override
        public void setupShadowTerrain(Viewport playerViewport, Viewport shadowViewport, CameraState cameraState,
                                       int frame, boolean spectator) {
            this.renderSectionManager.setShadowPass(true);

            try {
                super.setupShadowTerrain(playerViewport, shadowViewport, cameraState, frame, spectator);
            } finally {
                this.renderSectionManager.setShadowPass(false);
            }
        }
    }

    /**
     * Records the scheduling entry points of the passes so the upload order is observable, and builds every rebuild as
     * an empty section so an upload can be told apart from a dropped result without any geometry.
     */
    private static class RecordingRenderSectionManager extends RenderSectionManager {
        final List<String> events = new ArrayList<>();

        private boolean shadowPass;

        RecordingRenderSectionManager(CommandList commandList) {
            super(CONFIGURATION, () -> new ChunkBuildContext(CONFIGURATION), NoopChunkRenderer::new,
                    RENDER_DISTANCE, commandList, 0, MAX_SECTION, -1, true);
        }

        void setShadowPass(boolean shadowPass) {
            this.shadowPass = shadowPass;
        }

        @Override
        public boolean isInShadowPass() {
            return this.shadowPass;
        }

        @Override
        public void updateChunks(boolean updateImmediately) {
            this.events.add("updateChunks");
            super.updateChunks(updateImmediately);
        }

        @Override
        public void uploadChunks() {
            this.events.add("upload");
            super.uploadChunks();
        }

        @Override
        public void updateForShadowPass(Viewport playerViewport, Viewport shadowViewport, int frame, boolean spectator) {
            this.events.add("updateForShadowPass");
            super.updateForShadowPass(playerViewport, shadowViewport, frame, spectator);
        }

        @Override
        public void update(Viewport positionedViewport, int frame, boolean spectator) {
            this.events.add("update");
            super.update(positionedViewport, frame, spectator);
        }

        RenderListManager terrainLists() {
            return this.renderListManager;
        }

        RenderListManager shadowLists() {
            return this.shadowRenderListManager;
        }

        @Override
        protected AsyncOcclusionMode getAsyncOcclusionMode() {
            return AsyncOcclusionMode.NONE;
        }

        @Override
        protected boolean useFogOcclusion() {
            return false;
        }

        @Override
        protected boolean useRasterOcclusionCulling() {
            return false;
        }

        @Override
        protected boolean shouldUseOcclusionCulling(Viewport viewport, boolean spectator) {
            return false;
        }

        @Override
        public FogService getFogService() {
            return FOG_SERVICE;
        }

        @Override
        protected boolean isSectionVisuallyEmpty(int x, int y, int z) {
            return false;
        }

        @Override
        protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
            return new ChunkBuilderTask<>() {
                @Override
                public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
                    return new ChunkBuildOutput(render, RenderSection.EMPTY_DATA,
                            Reference2ReferenceMaps.emptyMap(), frame);
                }
            };
        }
    }

    /** A manager whose subclass hook reports that the shadow pass has nothing to search for. */
    private static final class GatedRenderSectionManager extends RecordingRenderSectionManager {
        private GatedRenderSectionManager(CommandList commandList) {
            super(commandList);
        }

        @Override
        protected boolean canSubmitShadowGraphSearch() {
            return false;
        }
    }

    private static final class NoopChunkRenderer implements ChunkRenderer {
        private final RenderPassConfiguration<?> configuration;

        private NoopChunkRenderer(RenderDevice device, RenderPassConfiguration<?> configuration) {
            this.configuration = configuration;
        }

        @Override
        public void render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                           TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera) {
            throw new UnsupportedOperationException("These tests never draw terrain");
        }

        @Override
        public void delete(CommandList commandList) {
        }

        @Override
        public RenderPassConfiguration<?> getRenderPassConfiguration() {
            return this.configuration;
        }
    }

    private static final class TestFogService implements FogService {
        @Override
        public float getFogEnd() {
            return RENDER_DISTANCE;
        }

        @Override
        public float getFogStart() {
            return 0.0f;
        }

        @Override
        public float getFogDensity() {
            return 1.0f;
        }

        @Override
        public int getFogShapeIndex() {
            return FOG_SHAPE_SPHERICAL;
        }

        @Override
        public float getFogCutoff() {
            return RENDER_DISTANCE;
        }

        @Override
        public float[] getFogColor() {
            return new float[] {0.0f, 0.0f, 0.0f, 0.0f};
        }

        @Override
        public ChunkShaderComponent.Factory<?> getFogMode() {
            return null;
        }
    }
}
