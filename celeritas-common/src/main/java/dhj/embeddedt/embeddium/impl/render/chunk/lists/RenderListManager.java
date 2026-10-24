package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import lombok.Getter;
import lombok.Setter;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.sorting.SortState;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;
import org.joml.Vector3ic;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Owns the render lists of one pass (terrain, or the shadow pass) and the search that produces them. The lattice
 * and search thread are shared between passes through {@link SectionGraph}.
 */
public class RenderListManager {
    @Getter
    @NotNull
    private SortedRenderLists renderLists;
    @Getter
    @NotNull
    private ChunkRebuildLists rebuildLists;

    private final SectionGraph graph;
    private final SectionLattice lattice;
    // Whether this manager produces the shadow pass's lists. The shadow search is orthographic and receiver-driven;
    // the terrain search is the camera-rooted one with the angular frustum clamp.
    private final boolean shadow;
    // Whether this pass's searches run on the shared search thread rather than inline.
    private final boolean async;

    // Non-null for the duration of an in-progress graph search (already completed when the search ran inline).
    private CompletableFuture<VisibleChunkCollector> currentOcclusionFuture;

    @Getter
    @Setter
    private boolean needsUpdate = true;

    @Getter
    private int lastUpdatedFrame;

    private int pendingLastUpdatedFrame;

    @NotNull
    private SectionLattice.VisibilitySnapshot visibilitySnapshot = SectionLattice.VisibilitySnapshot.EMPTY;

    // Snapshot produced by the in-progress search. Written on the async thread; read on the render thread
    // in finishPreviousGraphUpdate() after join() establishes happens-before, then published to visibilitySnapshot.
    @Nullable
    private SectionLattice.VisibilitySnapshot pendingVisibilitySnapshot;

    @Nullable
    private final SectionTicker sectionTicker;

    public record RenderListDebugStatistics(Object2IntOpenHashMap<TerrainRenderPass> renderPassCounts, int[] sortingSectionCounts) {
        public String getSortingString() {
            StringBuilder sb = new StringBuilder();

            sb.append("Sorting: ");
            String[] names = SortState.DEBUG_NAMES;
            for (int i = 0; i < names.length; i++) {
                sb.append(names[i]);
                sb.append('=');
                sb.append(sortingSectionCounts[i]);
                if((i + 1) < names.length) {
                    sb.append(", ");
                }
            }

            return sb.toString();
        }
    }

    private RenderListDebugStatistics debugStatistics;

    /**
     * @param graph  the lattice and search thread shared with the other pass's manager
     * @param shadow whether this manager serves the shadow pass
     * @param mode   which passes search asynchronously: {@code EVERYTHING} for both, {@code ONLY_SHADOW} for the
     *               shadow pass alone, {@code NONE} for neither
     */
    public RenderListManager(SectionGraph graph, boolean shadow, AsyncOcclusionMode mode, @Nullable SectionTicker sectionTicker) {
        this.graph = graph;
        this.lattice = graph.getLattice();
        this.shadow = shadow;
        this.async = mode == AsyncOcclusionMode.EVERYTHING || (shadow && mode == AsyncOcclusionMode.ONLY_SHADOW);
        this.sectionTicker = sectionTicker;
        this.renderLists = SortedRenderLists.empty();
        this.rebuildLists = ChunkRebuildLists.empty();
    }

    /**
     * Start the terrain search from the camera section. Only valid on the terrain manager.
     */
    public void startGraphUpdate(Viewport viewport, int frame, int regionIdsLength, float searchDistance, boolean useOcclusionCulling, int targetQueueSize) {
        if (this.shadow) {
            throw new IllegalStateException("startGraphUpdate is for the terrain pass; use startShadowGraphUpdate");
        }

        this.graph.prepareWindow(searchDistance, viewport.getChunkCoord());

        this.submitSearch(frame, regionIdsLength, targetQueueSize, viewport, visitor ->
                this.lattice.findVisible(visitor, viewport, searchDistance, regionIdsLength, useOcclusionCulling, true, frame));
    }

    /**
     * Start the shadow search. Only valid on the shadow manager, and only after the terrain manager has submitted
     * its search for this frame.
     *
     * <p>The lattice window is a resource shared with the terrain pass, and a search reads it for its whole
     * duration: this manager must not prepare it here, because the terrain search for this frame has already been
     * submitted and may still be running. The caller prepares every viewport the frame searches — including this one
     * — through {@link #prepareSearchWindow} before the first search of the frame is submitted.
     *
     * @param lightVector unit vector toward the shadow light, or {@code null} to run the frustum-only scan instead
     */
    public void startShadowGraphUpdate(Viewport shadowViewport, int frame, int regionIdsLength, float searchDistance, @Nullable Vector3fc lightVector, int targetQueueSize) {
        if (!this.shadow) {
            throw new IllegalStateException("startShadowGraphUpdate is for the shadow pass; use startGraphUpdate");
        }

        this.submitSearch(frame, regionIdsLength, targetQueueSize, shadowViewport, visitor ->
                this.lattice.findShadowVisible(visitor, shadowViewport, searchDistance, regionIdsLength, lightVector, frame));
    }

    /**
     * Prepare the shared lattice window so searches rooted at {@code viewports} can run against it.
     *
     * <p>Called once per frame, for every viewport that frame will search, before any of its searches is submitted,
     * so that no window rebase can run while a search is reading the lattice. See {@link SectionGraph#prepareWindow}.
     */
    public void prepareSearchWindow(float searchDistance, Viewport... viewports) {
        Vector3ic[] cameras = new Vector3ic[viewports.length];

        for (int i = 0; i < viewports.length; i++) {
            cameras[i] = viewports[i].getChunkCoord();
        }

        this.graph.prepareWindow(searchDistance, cameras);
    }

    private void submitSearch(int frame, int regionIdsLength, int targetQueueSize, Viewport viewport,
                              Function<VisibleChunkCollector, SectionLattice.VisibilitySnapshot> search) {
        if (this.currentOcclusionFuture != null) {
            throw new IllegalStateException("Occlusion work in progress while trying to submit next task");
        }

        var visitor = new VisibleChunkCollector(this.lattice, frame, regionIdsLength, targetQueueSize, viewport.getBlockCoord());

        Supplier<VisibleChunkCollector> occlusionTask = () -> {
            this.pendingVisibilitySnapshot = search.apply(visitor);

            // Sort the rebuild lists here rather than on the render thread when the result is joined
            visitor.finishRebuildLists();

            // WARNING: when async, this runs on the search thread.
            // SectionTicker.onRenderListUpdated() must be safe to call off the render thread.
            if (this.sectionTicker != null) {
                this.sectionTicker.onRenderListUpdated(visitor.getSortedRenderLists());
            }

            return visitor;
        };

        this.pendingLastUpdatedFrame = frame;
        this.currentOcclusionFuture = this.graph.submit(occlusionTask, this.async);

        if (!this.async) {
            this.finishPreviousGraphUpdate();
        }

        this.needsUpdate = false;
    }

    public void finishPreviousGraphUpdate() {
        if (currentOcclusionFuture != null) {
            VisibleChunkCollector visitor = currentOcclusionFuture.join();

            // The join establishes happens-before with the search thread, so the timing the search recorded
            // into the culler is visible here. Consume it on the render thread: GLSMPerfDebug's counters are
            // single-threaded and must never be written from the search thread.
            final long[] searchTiming = this.lattice.pollLastSearchTiming();
            if (searchTiming != null) {
                GLSMPerfDebug.record(GLSMPerfDebug.Stage.CHUNK_OCCLUSION_SEARCH, searchTiming[0], searchTiming[1]);
            }

            this.renderLists = visitor.createRenderLists();
            this.rebuildLists = visitor.getRebuildLists();

            // Publish the finished search's snapshot before advancing lastUpdatedFrame, so any concurrent
            // reader that observes the new frame also observes the snapshot produced for it.
            this.visibilitySnapshot = this.pendingVisibilitySnapshot;
            this.pendingVisibilitySnapshot = null;

            this.currentOcclusionFuture = null;
            this.lastUpdatedFrame = this.pendingLastUpdatedFrame;

            this.debugStatistics = null;

            this.graph.onSearchJoined();
        }

        // Run tasks deferred while searches were in flight. The join() above establishes happens-before,
        // so the search thread's writes to the lattice arrays are visible here. A no-op while the other
        // pass's search is still running.
        this.graph.runDeferredTasks();
    }

    public void destroy() {
        if (currentOcclusionFuture != null) {
            currentOcclusionFuture.join();
            currentOcclusionFuture = null;
            this.graph.onSearchJoined();
        }
    }

    public boolean isSectionVisible(int x, int y, int z) {
        return this.visibilitySnapshot.isSectionVisible(x, y, z, this.lastUpdatedFrame);
    }

    public void tickVisibleRenders() {
        if (this.sectionTicker != null) {
            this.sectionTicker.tickVisibleRenders();
        }
    }


    public RenderListDebugStatistics getDebugStatistics() {
        if (this.debugStatistics == null) {
            this.debugStatistics = computeDebugStatistics();
        }
        return this.debugStatistics;
    }

    public String getTickerDebugString() {
        if (this.sectionTicker == null) {
            return "";
        }
        return this.sectionTicker.getDebugString();
    }

    /** Current raster buffer size as {@code width x height} in pixels, or null when raster culling is off. */
    public @Nullable String rasterBufferSize() {
        return this.lattice.rasterBufferSize();
    }

    public int rasterBacktrackCount() {
        return this.lattice.rasterBacktrackCount();
    }

    private RenderListDebugStatistics computeDebugStatistics() {
        Object2IntOpenHashMap<TerrainRenderPass> renderPassCounts = new Object2IntOpenHashMap<>();

        var iterator = renderLists.iterator();

        int[] sectionCounts = new int[SortState.DEBUG_NAMES.length];

        boolean isSorting = renderLists.getPasses().stream().anyMatch(TerrainRenderPass::isSorted);

        while (iterator.hasNext()) {
            var renderList = iterator.next();

            if (renderList.getSectionsWithGeometryCount() == 0) {
                continue;
            }

            var region = renderList.getRegion();

            for (TerrainRenderPass pass : region.getPasses()) {
                int numToAdd = 0;
                var storage = region.getStorage(pass);
                var iter = Objects.requireNonNull(renderList.sectionsWithGeometryIterator());

                while (iter.hasNext()) {
                    int sectionIndex = iter.nextByteAsInt();
                    if (storage.getSliceMask(sectionIndex) != 0) {
                        numToAdd++;
                    }
                }

                if (numToAdd > 0) {
                    renderPassCounts.addTo(pass, numToAdd);
                }
            }

            if (isSorting) {
                var iter = Objects.requireNonNull(renderList.sectionsWithGeometryIterator());

                while (iter.hasNext()) {
                    int sectionIndex = iter.nextByteAsInt();
                    var section = region.getSection(sectionIndex);

                    if(section == null || section.getHighestSortingIndex() == RenderSection.NO_TRANSLUCENT_GEOMETRY) {
                        continue;
                    }

                    sectionCounts[section.getHighestSortingIndex()]++;
                }
            }
        }

        return new RenderListDebugStatistics(renderPassCounts, sectionCounts);
    }
}
