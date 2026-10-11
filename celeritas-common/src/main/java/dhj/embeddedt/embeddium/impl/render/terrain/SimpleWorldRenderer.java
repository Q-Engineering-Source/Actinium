package dhj.embeddedt.embeddium.impl.render.terrain;

import dhj.embeddedt.embeddium.impl.util.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongCollection;
import lombok.Getter;
import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.impl.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Provides an extension to a game's regular world renderer.
 */
public abstract class SimpleWorldRenderer<WORLD, SECTIONMANAGER extends RenderSectionManager, LAYER, BLOCKENTITY, BLOCKENTITY_RENDER_CONTEXT> {
    protected WORLD world;
    protected int renderDistance;

    public record CameraState(double x, double y, double z, double pitch, double yaw, float fogDistance) {}

    protected CameraState lastCameraState;

    protected Viewport currentViewport;

    @Getter
    protected SECTIONMANAGER renderSectionManager;

    private ChunkTracker.Subscription chunkTrackerSubscription;

    /**
     * Returns whether this renderer is currently attached to the supplied world instance.
     */
    public boolean isRenderingWorld(WORLD world) {
        return this.world == world;
    }

    public void setWorld(WORLD world) {
        // Check that the world is actually changing
        if (this.world == world) {
            return;
        }

        // If we have a world is already loaded, unload the renderer
        if (this.world != null) {
            this.unloadWorld();
        }

        // If we're loading a new world, load the renderer
        if (world != null) {
            this.loadWorld(world);
        }
    }

    protected void loadWorld(WORLD world) {
        this.world = world;
        this.chunkTrackerSubscription = ChunkTrackerHolder.get(world).subscribe();

        boolean loaded = false;
        try {
            try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
                this.initRenderer(commandList);
            }
            loaded = true;
        } finally {
            if (!loaded) {
                this.unloadWorld();
            }
        }
    }

    protected void unloadWorld() {
        try {
            if (this.renderSectionManager != null) {
                this.renderSectionManager.destroy();
            }
        } finally {
            this.renderSectionManager = null;
            try {
                Objects.requireNonNull(
                    this.chunkTrackerSubscription,
                    "Loaded world must have a chunk tracker subscription"
                ).unsubscribe();
            } finally {
                this.chunkTrackerSubscription = null;
                this.world = null;
            }
        }
    }

    /**
     * @return The number of chunk renders which are visible in the current camera's frustum
     */
    public int getVisibleChunkCount() {
        return this.renderSectionManager.getVisibleChunkCount();
    }

    /**
     * Notifies the chunk renderer that the graph scene has changed and should be re-computed.
     */
    public void scheduleTerrainUpdate() {
        // BUG: seems to be called before init
        if (this.renderSectionManager != null) {
            this.renderSectionManager.markGraphDirty();
        }
    }

    /**
     * @return True if no chunks are pending rebuilds
     */
    public boolean isTerrainRenderComplete() {
        return this.renderSectionManager.getBuilder().isBuildQueueEmpty();
    }

    public abstract int getEffectiveRenderDistance();

    /**
     * Called prior to any chunk rendering in order to update necessary state.
     */
    public void setupTerrain(Viewport viewport,
                             CameraState cameraState,
                             @Deprecated(forRemoval = true) int frame,
                             boolean spectator,
                             boolean updateChunksImmediately) {
        NativeBuffer.reclaim(false);

        // When a shadow pass preceded this one, it joined the previous frame's searches, applied the chunk events
        // and submitted this frame's searches; joining again here would stall on those, and chunk events must
        // be applied while no search is in flight.
        if (!this.renderSectionManager.didShadowPassRunThisFrame()) {
            this.renderSectionManager.finishAllGraphUpdates();
            this.processChunkEvents();
        }

        this.renderSectionManager.runAsyncTasks();

        if (getEffectiveRenderDistance() != this.renderDistance) {
            this.reload();
        }

        this.prepareFrame(viewport, cameraState, updateChunksImmediately);

        this.renderSectionManager.uploadChunks();

        if (this.renderSectionManager.needsUpdate()) {
            this.renderSectionManager.update(viewport, frame, spectator);
        }

        if (updateChunksImmediately) {
            this.renderSectionManager.uploadChunks();
        }

        this.renderSectionManager.tickVisibleRenders();
    }

    /**
     * Shadow-pass counterpart of {@link #setupTerrain}. The shadow pass precedes the terrain pass in a frame, so
     * this joins the previous frame's searches, applies chunk load/unload events while the lattice is quiescent,
     * and runs the terrain search for {@code playerViewport} when one is due; the terrain pass then reuses it.
     * Render-distance reloads and uploads are left to the terrain pass.
     *
     * @param playerViewport the player camera's viewport for this frame
     * @param shadowViewport the shadow frustum, centred on the player camera
     */
    public void setupShadowTerrain(Viewport playerViewport,
                                   Viewport shadowViewport,
                                   CameraState cameraState,
                                   @Deprecated(forRemoval = true) int frame,
                                   boolean spectator) {
        NativeBuffer.reclaim(false);

        // The shadow pass renders at a reduced render distance when configured, which limits the terrain search
        // radius while preserving the full-distance search for the main pass.
        int previousRenderDistance = this.renderDistance;
        int shadowRenderDistance = Math.max(1, this.getShadowEffectiveRenderDistance());
        boolean restoreRenderDistance = false;

        if (shadowRenderDistance != previousRenderDistance) {
            this.renderDistance = shadowRenderDistance;
            restoreRenderDistance = true;
        }

        try {
            this.renderSectionManager.finishAllGraphUpdates();

            this.processChunkEvents();

            this.prepareFrame(shadowViewport, cameraState, false);

            this.renderSectionManager.updateForShadowPass(playerViewport, shadowViewport, frame, spectator);

            this.renderSectionManager.tickVisibleRenders();
        } finally {
            if (restoreRenderDistance) {
                this.renderDistance = previousRenderDistance;
            }
        }
    }

    protected int getShadowEffectiveRenderDistance() {
        return this.getEffectiveRenderDistance();
    }

    // Steps common to both passes: camera-change detection, async task draining and chunk build scheduling.
    private void prepareFrame(Viewport viewport, CameraState cameraState, boolean updateChunksImmediately) {
        boolean dirty = this.lastCameraState == null || !this.lastCameraState.equals(cameraState);

        if (dirty) {
            this.renderSectionManager.markGraphDirty();
            this.lastCameraState = cameraState;
        }

        this.currentViewport = viewport;

        this.renderSectionManager.runAsyncTasks();

        this.renderSectionManager.updateChunks(updateChunksImmediately);
    }

    private void processChunkEvents() {
        Objects.requireNonNull(
            this.chunkTrackerSubscription,
            "Loaded world must have a chunk tracker subscription"
        ).forEachEvent(this.renderSectionManager::onChunkAdded, this.renderSectionManager::onChunkRemoved);
    }

    protected abstract ChunkRenderMatrices createChunkRenderMatrices();

    public Viewport getLastViewport() {
        return this.currentViewport;
    }

    /**
     * Performs a render pass for the given {@link LAYER} and draws all visible chunks for it.
     */
    public void drawChunkLayer(LAYER renderLayer, double x, double y, double z) {
        ChunkRenderMatrices matrices = createChunkRenderMatrices();

        Collection<TerrainRenderPass> passes = this.renderSectionManager.getRenderPassConfiguration().vanillaRenderStages().get(renderLayer);

        if (passes != null && !passes.isEmpty()) {
            var occlusionCamera = this.getLastViewport().getTransform();
            var realCamera = new CameraTransform(x, y, z);
            for (var pass : passes) {
                this.renderSectionManager.renderLayer(matrices, pass, occlusionCamera, realCamera);
            }
        }
    }

    public void reload() {
        if (this.world == null) {
            return;
        }

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    protected abstract SECTIONMANAGER createRenderSectionManager(CommandList commandList);

    protected void initRenderer(CommandList commandList) {
        if (this.renderSectionManager != null) {
            this.renderSectionManager.destroy();
            this.renderSectionManager = null;
        }

        this.renderDistance = getEffectiveRenderDistance();

        this.renderSectionManager = this.createRenderSectionManager(commandList);

        LongCollection readyChunks = Objects.requireNonNull(
            this.chunkTrackerSubscription,
            "Loaded world must have a chunk tracker subscription"
        ).snapshotReadyChunks();
        ChunkTracker.forEachChunk(readyChunks, this.renderSectionManager::onChunkAdded);
    }

    /**
     * {@return an iterator over all visible block entities}
     */
    public Iterator<BLOCKENTITY> blockEntityIterator() {
        return MinecraftBuiltRenderSectionData.generateBlockEntityIterator(this.renderSectionManager.getRenderLists(), this.renderSectionManager.getSectionsWithGlobalEntities());
    }

    public void forEachVisibleBlockEntity(Consumer<BLOCKENTITY> consumer) {
        MinecraftBuiltRenderSectionData.forEachBlockEntity(consumer, this.renderSectionManager.getRenderLists(), this.renderSectionManager.getSectionsWithGlobalEntities());
    }

    protected abstract void renderBlockEntityList(List<BLOCKENTITY> list, BLOCKENTITY_RENDER_CONTEXT context);

    /** Lets implementations retain the global-renderer classification produced during section builds. */
    protected void renderGlobalBlockEntityList(List<BLOCKENTITY> list, BLOCKENTITY_RENDER_CONTEXT context) {
        this.renderBlockEntityList(list, context);
    }

    private int renderCulledBlockEntities(BLOCKENTITY_RENDER_CONTEXT renderContext) {
        int count = 0;
        SortedRenderLists renderLists = this.renderSectionManager.getRenderLists();
        Iterator<ChunkRenderList> renderListIterator = renderLists.iterator();

        while (renderListIterator.hasNext()) {
            var renderList = renderListIterator.next();

            var renderRegion = renderList.getRegion();
            var renderSectionIterator = renderList.sectionsWithEntitiesIterator();

            if (renderSectionIterator == null) {
                continue;
            }

            while (renderSectionIterator.hasNext()) {
                var renderSectionId = renderSectionIterator.nextByteAsInt();
                var renderSection = renderRegion.getSection(renderSectionId);

                if (renderSection == null) {
                    continue;
                }

                var context = renderSection.getBuiltContext();

                if (!(context instanceof MinecraftBuiltRenderSectionData mcData)) {
                    continue;
                }

                List<BLOCKENTITY> blockEntities = mcData.culledBlockEntities;

                if (blockEntities.isEmpty()) {
                    continue;
                }

                count += blockEntities.size();

                this.renderBlockEntityList(blockEntities, renderContext);
            }
        }

        return count;
    }

    private int renderGlobalBlockEntities(BLOCKENTITY_RENDER_CONTEXT renderContext) {
        int count = 0;
        for (var renderSection : this.renderSectionManager.getSectionsWithGlobalEntities()) {
            var context = renderSection.getBuiltContext();

            if (!(context instanceof MinecraftBuiltRenderSectionData mcData)) {
                continue;
            }

            List<BLOCKENTITY> blockEntities = mcData.globalBlockEntities;

            if (blockEntities.isEmpty()) {
                continue;
            }

            count += blockEntities.size();

            this.renderGlobalBlockEntityList(blockEntities, renderContext);
        }

        return count;
    }

    public int renderBlockEntities(BLOCKENTITY_RENDER_CONTEXT renderContext) {
        int count = 0;
        count += this.renderCulledBlockEntities(renderContext);
        count += this.renderGlobalBlockEntities(renderContext);
        return count;
    }

    // the volume of a section multiplied by the number of sections to be checked at most
    public static final double MAX_ENTITY_CHECK_VOLUME = 16 * 16 * 16 * 15;

    public abstract int getMinimumBuildHeight();
    public abstract int getMaximumBuildHeight();

    public boolean isPointVisible(double x, double y, double z) {
        if (y < getMinimumBuildHeight() + 0.5D || y > getMaximumBuildHeight() - 0.5D) {
            return true;
        }

        return this.renderSectionManager.isSectionVisible(
                PositionUtil.posToSectionCoord(x),
                PositionUtil.posToSectionCoord(y),
                PositionUtil.posToSectionCoord(z)
        );
    }

    public boolean isBoxVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        // Boxes outside the valid world height will never map to a rendered chunk
        // Always render these boxes or they'll be culled incorrectly!
        if (y2 < getMinimumBuildHeight() + 0.5D || y1 > getMaximumBuildHeight() - 0.5D) {
            return true;
        }

        double entityVolume = (x2 - x1) * (y2 - y1) * (z2 - z1);
        if (entityVolume > MAX_ENTITY_CHECK_VOLUME) {
            return true;
        }

        int minX = PositionUtil.posToSectionCoord(x1 - 0.5D);
        int minY = PositionUtil.posToSectionCoord(y1 - 0.5D);
        int minZ = PositionUtil.posToSectionCoord(z1 - 0.5D);

        int maxX = PositionUtil.posToSectionCoord(x2 + 0.5D);
        int maxY = PositionUtil.posToSectionCoord(y2 + 0.5D);
        int maxZ = PositionUtil.posToSectionCoord(z2 + 0.5D);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (this.renderSectionManager.isSectionVisible(x, y, z)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public String getChunksDebugString() {
        // C: visible/total D: distance
        return String.format("C: %d/%d D: %d %s", this.renderSectionManager.getVisibleChunkCount(),
                this.renderSectionManager.getTotalSections(), this.renderDistance,
                this.renderSectionManager.getTickerDebugString());
    }

    public RenderPassConfiguration<?> getRenderPassConfiguration() {
        return this.renderSectionManager.getRenderPassConfiguration();
    }

    /**
     * Schedules chunk rebuilds for all chunks in the specified block region.
     */
    public void scheduleRebuildForBlockArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        this.scheduleRebuildForChunks(minX >> 4, minY >> 4, minZ >> 4, maxX >> 4, maxY >> 4, maxZ >> 4, important);
    }

    /**
     * Schedules chunk rebuilds for all chunks in the specified chunk region.
     */
    public void scheduleRebuildForChunks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkY = minY; chunkY <= maxY; chunkY++) {
                for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                    this.scheduleRebuildForChunk(chunkX, chunkY, chunkZ, important);
                }
            }
        }
    }

    /**
     * Schedules a chunk rebuild for the render belonging to the given chunk section position.
     */
    public void scheduleRebuildForChunk(int x, int y, int z, boolean important) {
        this.renderSectionManager.scheduleRebuild(x, y, z, important);
    }

    public Collection<String> getDebugStrings() {
        var debugStrings = new ArrayList<String>();
        if (this.currentViewport != null) {
            var transform = this.currentViewport.getTransform();
            debugStrings.add("Viewport: %.02f %.02f %.02f".formatted(transform.x, transform.y, transform.z));
        }
        if (this.renderSectionManager != null) {
            debugStrings.addAll(this.renderSectionManager.getDebugStrings());
        }
        return debugStrings;
    }

    public boolean isSectionReady(int x, int y, int z) {
        return this.renderSectionManager.isSectionBuilt(x, y, z);
    }

    /**
     * This interface should be implemented on the WorldRenderer or equivalent class.
     */
    public interface Provider<T extends SimpleWorldRenderer<?, ?, ?, ?, ?>> {
        T celeritas$getWorldRenderer();

        @SuppressWarnings("unchecked")
        static <T extends SimpleWorldRenderer<?, ?, ?, ?, ?>> @Nullable T getWorldRendererNullable(Object o) {
            return ((Provider<T>)o).celeritas$getWorldRenderer();
        }

        static <T extends SimpleWorldRenderer<?, ?, ?, ?, ?>> @NotNull T getWorldRenderer(Object o) {
            T result = getWorldRendererNullable(o);
            if (result == null) {
                throw new IllegalStateException("No renderer attached to active world");
            }
            return result;
        }
    }
}
