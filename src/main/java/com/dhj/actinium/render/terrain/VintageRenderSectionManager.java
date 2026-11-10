package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.render.chunk.*;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.fog.FogService;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.SectionTicker;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.CachedBatch;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCulling;
import dhj.embeddedt.embeddium.impl.render.culling.GpuTerrainCuller;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkFogMode;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.GenericSectionSpriteTicker;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.dhj.actinium.compat.depthsupdate.DepthsUpdateCompat;
import com.dhj.actinium.compat.fluxloading.FluxLoadingCompat;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.world.cloned.ChunkRenderContext;
import com.dhj.actinium.world.cloned.ClonedChunkSectionCache;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask;
import com.dhj.actinium.render.terrain.deferred.DeferredMeshScheduler;
import com.dhj.actinium.render.terrain.fog.GLStateManagerFogService;
import com.dhj.actinium.render.terrain.sprite.SpriteUtil;

import java.util.List;
import java.util.Objects;

public class VintageRenderSectionManager extends RenderSectionManager {
    private final WorldClient world;
    @Getter
    private final ClonedChunkSectionCache sectionCache;
    private int shadowSearchSubmissions;
    private final TerrainPassPreparation terrainPassPreparation = new TerrainPassPreparation();

    /**
     * Runs the render-thread capture of the deferred blocks and owns their pooled buffers and
     * snapshots. It outlives single builds: the pool is trimmed every frame and freed when the world
     * is left.
     */
    private final DeferredMeshScheduler deferredScheduler;

    /**
     * The context every deferred capture renders with. It is separate from the contexts of the build
     * threads so a capture cannot share render state with the build that handed a mesh over or with
     * the next build on that thread.
     */
    private final VintageChunkBuildContext captureContext;

    public VintageRenderSectionManager(RenderPassConfiguration<?> configuration, WorldClient world, int renderDistance, CommandList commandList, int minSection, int maxSection) {
        super(configuration, () -> new VintageChunkBuildContext(world, configuration), ChunkRenderer::new, renderDistance, commandList, minSection, maxSection, ActiniumRuntime.options().performance.chunkBuilderThreads, true);
        this.world = world;
        this.sectionCache = new ClonedChunkSectionCache(world);
        this.captureContext = new VintageChunkBuildContext(world, configuration);
        this.deferredScheduler = new DeferredMeshScheduler(configuration, this.getBuilder(), () -> new WorldSlice(world),
                this::scheduleAsyncTask, this::cancelAsyncTask, this.getBuildResults());
    }

    public static VintageRenderSectionManager create(ChunkVertexType vertexType, WorldClient world, int renderDistance, CommandList commandList) {
        int minSection = DepthsUpdateCompat.getMinSection(world);
        int maxSection = DepthsUpdateCompat.getMaxSection(world);
        return new VintageRenderSectionManager(VintageRenderPassConfigurationBuilder.build(vertexType), world, renderDistance, commandList, minSection, maxSection);
    }

    @Override
    public FogService getFogService() {
        return GLStateManagerFogService.INSTANCE;
    }

    @Override
    protected AsyncOcclusionMode getAsyncOcclusionMode() {
        return ActiniumRuntime.options().performance.asyncOcclusionMode;
    }

    @Override
    protected boolean useRasterOcclusionCulling() {
        return ActiniumRuntime.options().performance.useRasterOcclusionCulling;
    }

    @Override
    protected boolean shouldRespectUpdateTaskQueueSizeLimit() {
        return true;
    }

    @Override
    protected boolean useFogOcclusion() {
        if (!ActiniumRuntime.options().performance.useFogOcclusion) {
            return false;
        }

        if (ShaderProviderHolder.isShadowPass()) {
            // Iris/Sodium shadow rendering owns its caster distance through the shadow
            // frustum. The legacy fixed-function fog state belongs to the main view and
            // may truncate the shadow render list when it is reused here.
            return false;
        }

        if (!ShaderProviderHolder.isActive()) {
            return true;
        }

        return GLStateManagerFogService.INSTANCE.getFogMode() != ChunkFogMode.NONE;
    }

    @Override
    protected boolean shouldUseOcclusionCulling(Viewport positionedViewport, boolean spectator) {
        if (ShaderProviderHolder.isShadowPass()) {
            // Shadow casters must not depend on paths reachable from the player's view.
            // Sodium/Iris enumerate the shadow frustum independently of main-view
            // occlusion; retaining the BFS here drops open terrain from the shadow map.
            return false;
        }

        final boolean useOcclusionCulling;
        var camBlockPos = positionedViewport.getBlockCoord();
        BlockPos origin = new BlockPos(camBlockPos.x(), camBlockPos.y(), camBlockPos.z());

        if (spectator && this.world.getBlockState(origin).isOpaqueCube())
        {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = Minecraft.getMinecraft().renderChunksMany;
        }

        return useOcclusionCulling;
    }

    @Override
    protected boolean isSectionVisuallyEmpty(int x, int y, int z) {
        Chunk chunk = this.world.getChunk(x, z);
        if (chunk.isEmpty()) {
            return true;
        }
        var array = chunk.getBlockStorageArray();
        int storageIndex = DepthsUpdateCompat.toStorageIndex(this.world, y);
        if (storageIndex < 0 || storageIndex >= array.length) {
            return true;
        }
        return array[storageIndex] == Chunk.NULL_BLOCK_STORAGE || array[storageIndex].isEmpty();
    }

    @Override
    protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
        ChunkRenderContext context = WorldSlice.prepare(this.world, new SectionPos(render.getChunkX(), render.getChunkY(), render.getChunkZ()), this.sectionCache);

        if (context == null) {
            return null;
        }

        ChunkBuilderMeshingTask task = new ChunkBuilderMeshingTask(render, context, frame, this.cameraPosition,
                this.useRasterOcclusionCulling(), this.captureContext);
        // submitRebuildTasks keeps the pending update until the job was scheduled, so it is the type
        // this rebuild was queued for.
        task.bindScheduler(this.deferredScheduler,
                Objects.requireNonNull(render.getPendingUpdate(), "Pending update").isImportant(),
                () -> this.scheduleSectionForRebuild(render.getChunkX(), render.getChunkY(), render.getChunkZ(), false));
        return task;
    }

    @Override
    protected boolean allowImportantRebuilds() {
        return !ActiniumRuntime.options().performance.alwaysDeferChunkUpdates;
    }

    @Override
    protected void scheduleSectionForRebuild(int x, int y, int z, boolean important) {
        this.sectionCache.invalidate(x, y, z);
        super.scheduleSectionForRebuild(x, y, z, important);
    }

    @Override
    public void updateChunks(boolean updateImmediately) {
        this.sectionCache.cleanup();
        super.updateChunks(updateImmediately);
        this.deferredScheduler.update();
        FluxLoadingCompat.onRenderSectionManagerUpdateChunks();
    }

    /**
     * Stops the deferred pipeline before the shared executor goes away and releases what it still
     * holds.
     *
     * <p>The order matters: the scheduler has to refuse new captures first, then the base teardown
     * cancels and joins the build jobs, then the meshes that were still queued for their capture are
     * captured and released, and finally everything left is released with the pools. The capture
     * context is destroyed last because the release of those meshes may still render with it.</p>
     */
    @Override
    public void destroy() {
        this.deferredScheduler.markDestroyed();
        super.destroy();
        this.deferredScheduler.runImportant();
        this.deferredScheduler.releaseOrphans();
        this.captureContext.destroy();
    }

    /**
     * This ridiculous workaround is needed because some mods rely on side effects of calling getRenderBoundingBox
     * to initialize tile entity state. It can be removed if we start using getRenderBoundingBox for TE rendering
     * again.
     */
    @SuppressWarnings("unchecked")
    private static void retrieveBBForList(List<?> blockEntities) {
        if (!blockEntities.isEmpty()) {
            for (var be : (List<TileEntity>)blockEntities) {
                be.getRenderBoundingBox();
            }
        }
    }

    @Override
    protected boolean updateSectionInfo(RenderSection render, @Nullable BuiltRenderSectionData info) {
        if (info instanceof MinecraftBuiltRenderSectionData<?,?> mcData) {
            retrieveBBForList(mcData.culledBlockEntities);
            retrieveBBForList(mcData.globalBlockEntities);
        }
        return super.updateSectionInfo(render, info);
    }

    @Override
    protected @Nullable SectionTicker createSectionTicker() {
        return new GenericSectionSpriteTicker<>(SpriteUtil::markSpriteActive);
    }

    @Override
    public boolean isInShadowPass() {
        return ShaderProviderHolder.isShadowPass();
    }

    /** Marks only the shadow lists dirty: the shadow pass searches again, the terrain lists keep their own state. */
    public void markShadowGraphDirty() {
        if (this.shadowRenderListManager != null) {
            this.shadowRenderListManager.setNeedsUpdate(true);
        }
    }

    @Override
    protected boolean canSubmitShadowGraphSearch() {
        return super.canSubmitShadowGraphSearch() && this.shadowRenderListManager.isNeedsUpdate();
    }

    @Override
    protected void submitShadowGraphSearch(Viewport viewport, int frame) {
        super.submitShadowGraphSearch(viewport, frame);
        this.shadowSearchSubmissions++;
    }

    /** @return how many shadow searches this manager has submitted; a change across a shadow pass means new lists. */
    public int getShadowSearchSubmissions() {
        return this.shadowSearchSubmissions;
    }

    @Override
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera) {
        if (ShaderProviderHolder.isShadowPass()) {
            RenderDebugHooksHolder.logShadowTerrainLayer("before-render-layer", pass.name(), getVisibleChunkCount());
        }
        super.renderLayer(matrices, pass, occlusionCamera, camera);
    }

    /**
     * Marks the render lists of the terrain setup that just ran as needing their compute culling preparation.
     *
     * <p>The preparation waits for the first main terrain draw of the frame instead of running during the setup: the
     * shadow pass draws after the setup, and its walk over the shadow render lists discards prepared command ranges.
     */
    public void markTerrainPreparationPending() {
        this.terrainPassPreparation.markPending();
    }

    /**
     * Prepares the compute culling ranges of every pass of the current render lists, once per terrain setup.
     *
     * <p>Shadow draws leave the preparation pending, because they draw the shadow render lists and the next main draw
     * searches its own. A preparation that throws leaves it pending as well, so the next draw prepares again instead
     * of drawing ranges that were never culled.
     */
    public void prepareTerrainPasses(ChunkRenderMatrices matrices, CameraTransform occlusionCamera, CameraTransform camera) {
        this.terrainPassPreparation.prepareIfPending(this.isInShadowPass(),
            () -> this.dispatchTerrainPasses(matrices, occlusionCamera, camera));
    }

    /**
     * Runs the culling preparation of every pass of the current render lists through the chunk renderer of this
     * manager, which owns the culling backend.
     */
    private void dispatchTerrainPasses(ChunkRenderMatrices matrices, CameraTransform occlusionCamera, CameraTransform camera) {
        ((ChunkRenderer) this.chunkRenderer).prepareTerrainPasses(this.getRenderLists(), matrices, occlusionCamera, camera);
    }

    private static class ChunkRenderer extends DefaultChunkRenderer {
        private static final Logger LOGGER = LogManager.getLogger("ActiniumChunkRenderer");

        private GlProgram<? extends ChunkShaderInterface> actiniumProgram;
        private boolean usingActiniumProgram;
        private final GpuTerrainCuller culler = createCuller();
        private final ReusableCachedBatch gpuBatch = new ReusableCachedBatch();

        public ChunkRenderer(RenderDevice device, RenderPassConfiguration<?> renderPassConfiguration) {
            super(device, renderPassConfiguration, GLStateManagerFogService.INSTANCE);

            ShaderProvider provider = ShaderProviderHolder.getProvider();
            if (provider != null) {
                provider.setRenderPassConfiguration(renderPassConfiguration);
            }
        }

        private static @Nullable GpuTerrainCuller createCuller() {
            GpuCulling.Availability availability = GpuCulling.availability();
            if (availability != GpuCulling.Availability.AVAILABLE) {
                LOGGER.info("GPU chunk culling unavailable ({}), terrain stays on CPU culling", availability);
                // The renderer this call belongs to never reaches the compute path, so nothing may keep the mirror and
                // the compute program of an earlier renderer alive. The selected mode stays as configured.
                GpuCulling.shutdown();
                return null;
            }

            LOGGER.info("GPU chunk culling available, mode={}", GpuCulling.mode());
            return new GpuTerrainCuller(GpuCulling.culler(), GpuCulling.sectionMeta());
        }

        /**
         * Prepares the command ranges of every pass of {@code renderLists} for the draw that is about to start.
         */
        void prepareTerrainPasses(ChunkRenderListIterable renderLists, ChunkRenderMatrices matrices,
                                  CameraTransform occlusionCamera, CameraTransform camera) {
            if (this.culler == null) {
                return;
            }

            this.culler.prepareAllPasses(matrices, renderLists, occlusionCamera, camera, this.useBlockFaceCulling());
        }

        @Override
        public void render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                           TerrainRenderPass renderPass, CameraTransform occlusionCamera, CameraTransform camera) {
            if (this.culler != null) {
                this.culler.beginRenderPass(matrices, renderLists, renderPass, occlusionCamera, camera, this.useBlockFaceCulling());
            }

            try {
                super.render(matrices, commandList, renderLists, renderPass, occlusionCamera, camera);
            } finally {
                if (this.culler != null) {
                    this.culler.endPass();
                }
            }
        }

        @Override
        protected @Nullable CachedBatch getRegionBatch(CommandList commandList,
                                                       RenderRegion region,
                                                       SectionRenderDataStorage storage,
                                                       ChunkRenderList renderList,
                                                       CameraTransform occlusionCamera,
                                                       TerrainRenderPass renderPass,
                                                       SectionRenderDataStorage.BatchCacheParams cacheParams) {
            if (this.culler != null && this.culler.isComputeActiveThisPass()) {
                return this.gpuBatch.wrap(this.culler.batchForRegion(region));
            }

            return super.getRegionBatch(commandList, region, storage, renderList, occlusionCamera, renderPass, cacheParams);
        }

        @Override
        public void delete(CommandList commandList) {
            super.delete(commandList);

            if (this.culler != null) {
                this.culler.delete();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void begin(TerrainRenderPass pass) {
            ShaderProvider provider = ShaderProviderHolder.getProvider();
            if (provider != null && provider.isShadersEnabled()) {
                GlProgram<? extends ChunkShaderInterface> override = provider.getShaderOverride(pass);
                if (override != null) {
                    pass.startDrawing();
                    override.bind();
                    override.getInterface().setupState(pass);
                    this.activeProgram = (GlProgram<ChunkShaderInterface>) override;
                    this.actiniumProgram = override;
                    this.usingActiniumProgram = true;
                    return;
                }
            }

            this.usingActiniumProgram = false;
            this.actiniumProgram = null;
            super.begin(pass);
        }

        @Override
        protected void end(TerrainRenderPass pass) {
            if (this.usingActiniumProgram && this.actiniumProgram != null) {
                RenderDebugHooksHolder.logCurrentFramebufferSamples("terrain:" + pass.name(), 1);
                this.actiniumProgram.getInterface().restoreState();
                this.actiniumProgram.unbind();
                this.actiniumProgram = null;
                this.usingActiniumProgram = false;
                this.activeProgram = null;
                pass.endDrawing();
                return;
            }

            super.end(pass);
        }

        @Override
        public boolean useBlockFaceCulling(){
            return ActiniumRuntime.options().performance.useBlockFaceCulling && ShaderProviderHolder.shouldUseFaceCulling();
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
            shader.setTextureSlot(ChunkShaderTextureSlot.BLOCK, 0);
            shader.setTextureSlot(ChunkShaderTextureSlot.LIGHT, 1);
        }

        /**
         * Carries the compute culler's per-region indirect draw range into the CPU renderer's batch loop. The batch
         * object is reused for every region, since the culler owns the ranges and this wrapper only hands them over.
         */
        private static final class ReusableCachedBatch extends CachedBatch {
            private static final byte[] NO_SECTIONS = new byte[0];

            private MultiDrawBatch batch;

            ReusableCachedBatch() {
                // The culler owns the draw ranges and this wrapper carries none of the CPU-side buffers, so the batch
                // has no tessellation of its own. The render loop prepares the pass's tessellation when it is null.
                super(null, null, NO_SECTIONS, 0, 0, 0, 0, 0, 0, 0);
            }

            CachedBatch wrap(@Nullable MultiDrawBatch batch) {
                this.batch = batch;
                return this;
            }

            @Override
            public @Nullable MultiDrawBatch getBatch() {
                return this.batch;
            }
        }
    }
}

