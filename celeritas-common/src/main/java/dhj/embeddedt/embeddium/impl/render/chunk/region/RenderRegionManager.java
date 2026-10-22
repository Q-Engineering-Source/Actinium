package dhj.embeddedt.embeddium.impl.render.chunk.region;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.debug.TerrainBufferDiagnostics;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferArena;
import dhj.embeddedt.embeddium.impl.gl.arena.PendingUpload;
import dhj.embeddedt.embeddium.impl.gl.arena.staging.FallbackStagingBuffer;
import dhj.embeddedt.embeddium.impl.gl.arena.staging.MappedStagingBuffer;
import dhj.embeddedt.embeddium.impl.gl.arena.staging.StagingBuffer;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkSortOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.jetbrains.annotations.NotNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class RenderRegionManager {
    private static final Logger LOGGER = LogManager.getLogger(RenderRegionManager.class);
    private static final AtomicLong NEXT_DIAGNOSTICS_OWNER_ID = new AtomicLong(1L);
    private static final TerrainArenaRetirementTracker ARENA_RETIREMENTS = TerrainArenaRetirementTracker.shared();

    private final Long2ReferenceOpenHashMap<RenderRegion> regions = new Long2ReferenceOpenHashMap<>();
    private final BitSet regionIds = new BitSet();
    private final long diagnosticsOwnerId = nextDiagnosticsOwnerId();
    private int nextFreeId = 0;

    private final StagingBuffer stagingBuffer;
    private final GlBufferArena.ResizeObserver resizeObserver = new GlBufferArena.ResizeObserver() {
        @Override
        public void onResize(GlBufferArena.ResizeEvent event) {
            RenderRegionManager.this.recordArenaResize(event);
        }

        @Override
        public boolean shouldTrackRetirement() {
            return GLSMPerfDebug.isEnabled() && ARENA_RETIREMENTS.hasCapacity();
        }

        @Override
        public void onRetirementFenceSubmitted(GlBufferArena.ReplacementFenceEvent event) {
            RenderRegionManager.this.recordArenaRetirementFence(event);
        }

        @Override
        public void onRetirementCoverageSkipped(GlBufferArena.ReplacementFenceEvent event) {
            RenderRegionManager.this.recordArenaRetirementCoverageSkipped(event);
        }

        @Override
        public void onOldBufferDeleteRequested(GlBufferArena.ReplacementDeleteEvent event) {
            ARENA_RETIREMENTS.recordDeleteRequested(event.resizeToken().resizeSequence(),
                    event.resizeToken().oldRegistrationToken(), event.oldBufferId(), event.requestedAtNanos());
        }
    };

    private final RenderPassConfiguration<?> renderPassConfiguration;

    public RenderRegionManager(CommandList commandList, RenderPassConfiguration<?> renderPassConfiguration) {
        this.stagingBuffer = createStagingBuffer(commandList);
        this.renderPassConfiguration = renderPassConfiguration;
    }

    public void update() {
        this.tickArenaRetirements();
        this.stagingBuffer.flip();

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            Iterator<RenderRegion> it = this.regions.values()
                    .iterator();

            while (it.hasNext()) {
                RenderRegion region = it.next();
                region.update(commandList);

                if (region.isEmpty()) {
                    region.delete(commandList);

                    it.remove();

                    this.regionIds.clear(region.getId());
                    this.nextFreeId = Math.min(this.nextFreeId, region.getId());
                }
            }
        }
    }

    public void uploadMeshes(CommandList commandList, Collection<ChunkJobResult.Success<? extends ChunkTaskOutput>> results, Runnable graphUpdateTrigger) {
        final long perfStart = GLSMPerfDebug.isEnabled() ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.CHUNK_UPLOAD) : 0L;
        this.stagingBuffer.beginUploadBatch();
        try {
            for (var entry : this.createMeshUploadQueues(results)) {
                new MeshUploader(commandList, entry.getKey(), graphUpdateTrigger).processResults(entry.getValue());
            }
        } finally {
            try {
                this.stagingBuffer.endUploadBatch(commandList);
            } finally {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.CHUNK_UPLOAD, perfStart);
            }
        }
    }

    /* Copied from fastutil 8 as we don't have access to it when limited to fastutil 7 */
    private static <K, V> ObjectIterable<Reference2ReferenceMap.Entry<K, V>> fastIterable(Reference2ReferenceMap<K, V> map) {
        final ObjectSet<Reference2ReferenceMap.Entry<K, V>> entries = map.reference2ReferenceEntrySet();
        return entries instanceof Reference2ReferenceMap.FastEntrySet ? () -> ((Reference2ReferenceMap.FastEntrySet<K, V>)entries).fastIterator() : entries;
    }

    private class MeshUploader {
        private final Map<GlVertexFormat, List<PendingSectionUpload>> uploadsByFormat = new Object2ObjectOpenHashMap<>(2);
        private final CommandList commandList;
        private final RenderRegion region;
        private final Runnable graphUpdateTrigger;

        private boolean needIndexBuffer;

        private MeshUploader(CommandList commandList, RenderRegion region, Runnable graphUpdateTrigger) {
            this.commandList = commandList;
            this.region = region;
            this.graphUpdateTrigger = graphUpdateTrigger;
        }

        private List<PendingSectionUpload> getUploadQueue(TerrainRenderPass pass) {
            return uploadsByFormat.computeIfAbsent(pass.vertexType().getVertexFormat(), $ -> new ObjectArrayList<>());
        }

        private void processBuildResult(ChunkBuildOutput result) {
            // Delete all existing data for the section in the region
            region.removeMeshes(result.render.getSectionIndex());

            // Add uploads for any new data
            for (var entry : fastIterable(result.meshes)) {
                BuiltSectionMeshParts mesh = Objects.requireNonNull(entry.getValue());

                needIndexBuffer |= mesh.indexBuffer() != null;

                getUploadQueue(entry.getKey()).add(new PendingMeshRebuildUpload(result.render, mesh, entry.getKey(),
                        PendingUpload.of(mesh.vertexBuffer()), PendingUpload.of(mesh.indexBuffer())));
            }
        }

        private void processSortResult(ChunkSortOutput result) {
            needIndexBuffer = true;

            for (var entry : fastIterable(result.meshes)) {
                var pass = entry.getKey();
                var mesh = entry.getValue();

                var storage = region.getStorage(pass);

                if (storage != null) {
                    storage.removeIndexBuffer(result.render.getSectionIndex());
                }

                getUploadQueue(entry.getKey()).add(new PendingMeshSortUpload(result.render, pass, PendingUpload.of(mesh.indexData())));
            }
        }

        public void processResults(Collection<? extends ChunkTaskOutput> results) {
            for (ChunkTaskOutput output : results) {
                if (output instanceof ChunkBuildOutput result) {
                    processBuildResult(result);
                } else if (output instanceof ChunkSortOutput result) {
                    processSortResult(result);
                } else {
                    throw new IllegalStateException("Unexpected result type: " + output.getClass().getName());
                }
            }

            // If we have nothing to upload, abort!
            if (uploadsByFormat.isEmpty()) {
                return;
            }

            boolean bufferChanged = false;

            for (var entry : uploadsByFormat.entrySet()) {
                var resources = region.createResources(entry.getKey(), commandList);
                var uploads = entry.getValue();
                var geometryArena = resources.getGeometryArena();

                bufferChanged |= geometryArena.upload(commandList, uploads.stream()
                        .map(PendingSectionUpload::vertexUpload).filter(Objects::nonNull));

                if (needIndexBuffer) {
                    bufferChanged |= resources.getOrCreateIndexArena(commandList).upload(commandList, uploads.stream()
                            .map(PendingSectionUpload::indexUpload).filter(Objects::nonNull));
                }
            }


            // If any of the buffers changed, the tessellation will need to be updated
            // Once invalidated the tessellation will be re-created on the next attempted use
            if (bufferChanged) {
                region.refresh(commandList);
            }

            int previousPassCookie = region.getPassSetUpdateCount();

            // Collect the upload results
            for (var uploads : uploadsByFormat.values()) {
                for (PendingSectionUpload upload : uploads) {
                    var storage = region.createStorage(upload.pass(), renderPassConfiguration);
                    if (upload instanceof PendingMeshRebuildUpload meshUpload) {
                        // Replace meshes
                        var indexResult = upload.indexUpload() != null ? upload.indexUpload().getResult() : null;
                        storage.setMeshes(upload.section().getSectionIndex(),
                                upload.vertexUpload().getResult(), indexResult, meshUpload.meshData().ranges());
                    } else if (upload instanceof PendingMeshSortUpload) {
                        // Replace index buffer
                        storage.replaceIndexBuffer(upload.section().getSectionIndex(), upload.indexUpload().getResult());
                    } else {
                        throw new IllegalStateException();
                    }
                }
            }

            region.removeEmptyStorages();

            if (region.getPassSetUpdateCount() != previousPassCookie) {
                graphUpdateTrigger.run();
            }
        }
    }

    private Reference2ReferenceMap.FastEntrySet<RenderRegion, List<ChunkTaskOutput>> createMeshUploadQueues(Collection<ChunkJobResult.Success<? extends ChunkTaskOutput>> results) {
        var map = new Reference2ReferenceOpenHashMap<RenderRegion, List<ChunkTaskOutput>>();

        for (var holder : results) {
            var result = holder.output();
            var queue = map.computeIfAbsent(result.render.getRegion(), k -> new ObjectArrayList<>());
            queue.add(result);
        }

        return map.reference2ReferenceEntrySet();
    }

    public void delete(CommandList commandList) {
        for (RenderRegion region : this.regions.values()) {
            region.delete(commandList);
        }

        this.regions.clear();
        this.stagingBuffer.delete(commandList);
        this.logRetirementCompletions(ARENA_RETIREMENTS.endOwner(this.diagnosticsOwnerId,
                currentShareGroupId(), System.nanoTime(), "region-manager-destroyed"));
    }

    public Collection<RenderRegion> getLoadedRegions() {
        return this.regions.values();
    }

    public StagingBuffer getStagingBuffer() {
        return this.stagingBuffer;
    }

    /** Scans live region arenas and staging state for the caller's gated diagnostic report. */
    public BufferMemorySnapshot getBufferMemorySnapshot() {
        int geometryArenaCount = 0;
        int indexArenaCount = 0;
        long geometryUsedBytes = 0L;
        long geometryCapacityBytes = 0L;
        long indexUsedBytes = 0L;
        long indexCapacityBytes = 0L;

        for (RenderRegion region : this.regions.values()) {
            for (RenderRegion.DeviceResources resources : region.getAllResources()) {
                GlBufferArena geometryArena = resources.getGeometryArena();
                geometryArenaCount++;
                geometryUsedBytes += geometryArena.getDeviceUsedMemoryL();
                geometryCapacityBytes += geometryArena.getDeviceAllocatedMemoryL();

                GlBufferArena indexArena = resources.getIndexArena();
                if (indexArena != null) {
                    indexArenaCount++;
                    indexUsedBytes += indexArena.getDeviceUsedMemoryL();
                    indexCapacityBytes += indexArena.getDeviceAllocatedMemoryL();
                }
            }
        }

        StagingMemorySnapshot staging = this.getStagingMemorySnapshot();
        long terrainBufferCapacityBytes = geometryCapacityBytes + indexCapacityBytes
                + staging.ringCapacityBytes() + staging.fallbackLiveBytes();

        return new BufferMemorySnapshot(this.regions.size(), geometryArenaCount, geometryUsedBytes,
                geometryCapacityBytes, indexArenaCount, indexUsedBytes, indexCapacityBytes,
                terrainBufferCapacityBytes, staging, ArenaRetirementSnapshot.from(ARENA_RETIREMENTS.snapshot()));
    }

    private void tickArenaRetirements() {
        boolean diagnosticsEnabled = GLSMPerfDebug.isEnabled();
        if (!diagnosticsEnabled && !ARENA_RETIREMENTS.hasWork()) {
            return;
        }
        this.logRetirementCompletions(ARENA_RETIREMENTS.tick(System.nanoTime(), currentShareGroupId(), diagnosticsEnabled));
    }

    private void recordArenaRetirementFence(GlBufferArena.ReplacementFenceEvent event) {
        GlBufferArena.ArenaDiagnosticsContext context = event.resize().context();
        TerrainBufferDiagnostics.ResizeToken token = event.resizeToken();
        TerrainArenaRetirementTracker.RetirementDescriptor descriptor = retirementDescriptor(event, token, context);

        GLContextIdentity identity = GLContextRegistryProvider.get().currentBinding();
        long shareGroupId = identity == null ? 0L : identity.shareGroupId();
        long contextGeneration = identity == null ? 0L : identity.generation();
        TerrainArenaRetirementTracker.FenceProbe fenceProbe = event.fence() == null ? null : new TerrainArenaRetirementTracker.FenceProbe() {
            @Override
            public boolean isCompleted() {
                return event.fence().isCompleted();
            }

            @Override
            public void delete() {
                event.fence().delete();
            }
        };

        try {
            if (!ARENA_RETIREMENTS.recordFence(descriptor, fenceProbe, shareGroupId, contextGeneration,
                    event.submittedAtNanos(), event.unavailableReason())) {
                this.deleteDiagnosticFence(event);
                ARENA_RETIREMENTS.recordCoverageSkipped(descriptor);
                LOGGER.info("[ActiniumMemory] terrain.arenaRetirement event=coverage-skipped reason=record-capacity arenaId={} resizeSequence={} oldBufferId={} oldCapacityBytes={}",
                        event.arenaId(), token.resizeSequence(), event.oldBufferId(), event.resize().oldCapacityBytes());
            }
        } catch (RuntimeException exception) {
            ARENA_RETIREMENTS.recordCoverageSkipped(descriptor);
            this.deleteDiagnosticFence(event);
            LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=fence-record arenaId={} resizeSequence={} reason={}",
                    event.arenaId(), token.resizeSequence(), exception.getClass().getName());
        }
    }

    private void recordArenaRetirementCoverageSkipped(GlBufferArena.ReplacementFenceEvent event) {
        TerrainBufferDiagnostics.ResizeToken token = event.resizeToken();
        ARENA_RETIREMENTS.recordCoverageSkipped(retirementDescriptor(event, token, event.resize().context()));
        LOGGER.info("[ActiniumMemory] terrain.arenaRetirement event=coverage-skipped reason=record-capacity arenaId={} resizeSequence={} oldBufferId={} oldCapacityBytes={}",
                event.arenaId(), token.resizeSequence(), event.oldBufferId(), event.resize().oldCapacityBytes());
    }

    private void deleteDiagnosticFence(GlBufferArena.ReplacementFenceEvent event) {
        if (event.fence() == null) {
            return;
        }
        try {
            event.fence().delete();
        } catch (RuntimeException exception) {
            LOGGER.warn("[ActiniumMemory] terrain.arenaObservationUnavailable phase=fence-record-cleanup arenaId={} resizeSequence={} reason={}",
                    event.arenaId(), event.resizeToken().resizeSequence(), exception.getClass().getName());
        }
    }

    private static TerrainArenaRetirementTracker.RetirementDescriptor retirementDescriptor(
            GlBufferArena.ReplacementFenceEvent event, TerrainBufferDiagnostics.ResizeToken token,
            GlBufferArena.ArenaDiagnosticsContext context) {
        return new TerrainArenaRetirementTracker.RetirementDescriptor(
                event.ownerId(), event.arenaId(), context.arenaType().name().toLowerCase(Locale.ROOT),
                event.resize().stride(), context.regionId(), context.regionX(), context.regionY(), context.regionZ(),
                token.resizeSequence(), token.oldRegistrationToken(), token.newRegistrationToken(),
                event.oldBufferId(), event.newBufferId(), event.resize().oldCapacityBytes(),
                event.resize().newCapacityBytes(), event.resize().usedBytes(), event.resize().requestBytes(),
                event.resize().copySpanCount(), token.baseline());
    }

    private void logRetirementCompletions(List<TerrainArenaRetirementTracker.Completion> completions) {
        for (TerrainArenaRetirementTracker.Completion completion : completions) {
            TerrainArenaRetirementTracker.RetirementDescriptor descriptor = completion.descriptor();
            String deleteOutcome = completion.deleteEventUnknown() ? "unknown"
                    : completion.deleteFailed() ? "java-failure"
                    : completion.deleteReturned() ? "returned" : "pending";
            LOGGER.info("[ActiniumMemory] terrain.arenaRetirement event={} ownerId={} arenaId={} arenaType={} stride={} regionId={} region=({},{},{}) resizeSequence={} baseline={} oldBufferId={} newBufferId={} oldCapacityBytes={} newCapacityBytes={} usedBytes={} requestBytes={} copySpanCount={} shareGroup={} contextGeneration={} submittedAtNanos={} lastUnsignaledAtNanos={} observedSignalAtNanos={} signalWindowLowerBoundNanos={} signalWindowUpperBoundNanos={} deleteRequested={} deleteRequestedAtNanos={} actualDeleteOutcome={} actualDeleteAtNanos={} deleteEventUnknown={} observationUnavailable={} observationUnavailableReason={} observationEndReason={} note=queue-commands-complete-does-not-prove-physical-reclaim",
                    completion.status(), descriptor.ownerId(), descriptor.arenaId(), descriptor.arenaType(),
                    descriptor.stride(), descriptor.regionId(), descriptor.regionX(), descriptor.regionY(),
                    descriptor.regionZ(), descriptor.resizeSequence(), descriptor.baseline(),
                    descriptor.oldBufferId(), descriptor.newBufferId(), descriptor.oldCapacityBytes(),
                    descriptor.newCapacityBytes(), descriptor.usedBytes(), descriptor.requestBytes(),
                    descriptor.copySpanCount(), completion.shareGroupId(), completion.contextGeneration(),
                    completion.submittedAtNanos(), completion.lastUnsignaledAtNanos(),
                    completion.observedSignalAtNanos(), completion.signalWindowLowerBoundNanos(),
                    completion.signalWindowUpperBoundNanos(), completion.deleteRequested(),
                    completion.deleteRequestedAtNanos(), deleteOutcome, completion.deleteReturnedAtNanos(),
                    completion.deleteEventUnknown(), completion.observationUnavailable(),
                    completion.observationUnavailableReason(), completion.observationEndReason());
        }
    }

    private static long currentShareGroupId() {
        GLContextIdentity identity = GLContextRegistryProvider.get().currentBinding();
        return identity == null ? 0L : identity.shareGroupId();
    }

    private static long nextDiagnosticsOwnerId() {
        long ownerId = NEXT_DIAGNOSTICS_OWNER_ID.getAndIncrement();
        if (ownerId <= 0L) {
            throw new IllegalStateException("Terrain region-manager diagnostic identity overflowed");
        }
        return ownerId;
    }

    private StagingMemorySnapshot getStagingMemorySnapshot() {
        if (this.stagingBuffer instanceof MappedStagingBuffer mapped) {
            long ringCapacityBytes = mapped.getRingCapacityBytes();
            long ringPendingUploadBytes = mapped.getRingPendingUploadBytes();
            long ringUnfencedTransferredBytes = mapped.getRingUnfencedTransferredBytes();
            long ringFencedBytes = mapped.getRingFencedBytes();
            return new StagingMemorySnapshot("mapped", ringCapacityBytes, mapped.getRingAvailableBytes(),
                    ringPendingUploadBytes, mapped.getRingPendingCopyCount(), ringUnfencedTransferredBytes,
                    mapped.getRingFenceCount(), ringFencedBytes, ringPendingUploadBytes
                    + ringUnfencedTransferredBytes + ringFencedBytes, mapped.getFallbackLiveBytes(),
                    mapped.getFallbackUploadCountTotal(), mapped.getFallbackUploadBytesTotal(),
                    mapped.isFallbackResetPending());
        }

        FallbackStagingBuffer fallback = (FallbackStagingBuffer) this.stagingBuffer;
        return new StagingMemorySnapshot("fallback", 0L, 0L, 0L, 0, 0L, 0, 0L, 0L,
                fallback.getLiveBytes(), fallback.getUploadCountTotal(), fallback.getUploadBytesTotal(),
                fallback.isResetPending());
    }

    private void recordArenaResize(GlBufferArena.ResizeEvent event) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }

        BufferMemorySnapshot snapshot = this.getBufferMemorySnapshot();
        String reason = event.newCapacityBytes() > event.oldCapacityBytes() ? "growth" : "compaction";
        long arenaResizeOldAndNewCapacitySumEstimateBytes = snapshot.terrainBufferCapacityBytes()
                + event.newCapacityBytes();
        StagingMemorySnapshot staging = snapshot.staging();
        GlBufferArena.ArenaDiagnosticsContext context = event.context();

        LOGGER.info("[ActiniumMemory] terrain.arenaResize kind={} reason={} region={}({},{},{}) stride={} oldCapacityBytes={} newCapacityBytes={} usedBytes={} requestBytes={} copySpans={} loadedRegions={} globalGeometryArenaCount={} globalGeometryUsedBytes={} globalGeometryCapacityBytes={} globalIndexArenaCount={} globalIndexUsedBytes={} globalIndexCapacityBytes={} stagingRingCapacityBytes={} stagingRingPendingUploadBytes={} stagingRingUnfencedTransferredBytes={} stagingRingFenceCount={} stagingRingInFlightBytes={} stagingRingFencedBytes={} stagingFallbackLiveBytes={} globalTerrainBufferCapacityBytes={} arenaResizeOldAndNewCapacitySumEstimateBytes={}",
                context.arenaType(), reason, context.regionId(), context.regionX(), context.regionY(), context.regionZ(),
                event.stride(), event.oldCapacityBytes(), event.newCapacityBytes(), event.usedBytes(),
                event.requestBytes(), event.copySpanCount(), snapshot.loadedRegionCount(),
                snapshot.geometryArenaCount(), snapshot.geometryUsedBytes(), snapshot.geometryCapacityBytes(),
                snapshot.indexArenaCount(), snapshot.indexUsedBytes(), snapshot.indexCapacityBytes(),
                staging.ringCapacityBytes(), staging.ringPendingUploadBytes(), staging.ringUnfencedTransferredBytes(),
                staging.ringFenceCount(), staging.ringInFlightBytes(), staging.ringFencedBytes(),
                staging.fallbackLiveBytes(), snapshot.terrainBufferCapacityBytes(),
                arenaResizeOldAndNewCapacitySumEstimateBytes);
    }

    public RenderRegion createForChunk(int chunkX, int chunkY, int chunkZ) {
        return this.create(chunkX >> RenderRegion.REGION_WIDTH_SH,
                chunkY >> RenderRegion.REGION_HEIGHT_SH,
                chunkZ >> RenderRegion.REGION_LENGTH_SH);
    }

    private int getNextId() {
        int id = this.nextFreeId;
        this.nextFreeId = this.regionIds.nextClearBit(id + 1);
        this.regionIds.set(id);
        return id;
    }

    @NotNull
    private RenderRegion create(int x, int y, int z) {
        var key = RenderRegion.key(x, y, z);
        var instance = this.regions.get(key);

        if (instance == null) {
            this.regions.put(key, instance = new RenderRegion(x, y, z, this.getNextId(), this.stagingBuffer,
                    this.resizeObserver, this.diagnosticsOwnerId));
        }

        return instance;
    }

    public int getRegionIdsLength() {
        return this.regionIds.length();
    }

    private interface PendingSectionUpload {
        RenderSection section();
        TerrainRenderPass pass();
        PendingUpload vertexUpload();
        PendingUpload indexUpload();
    }

    private record PendingMeshRebuildUpload(RenderSection section, BuiltSectionMeshParts meshData, TerrainRenderPass pass,
                                            PendingUpload vertexUpload, PendingUpload indexUpload) implements PendingSectionUpload {}

    private record PendingMeshSortUpload(RenderSection section, TerrainRenderPass pass, PendingUpload indexUpload) implements PendingSectionUpload {
        @Override
        public PendingUpload vertexUpload() {
            return null;
        }
    }


    private static StagingBuffer createStagingBuffer(CommandList commandList) {
        if (MappedStagingBuffer.isSupported(RenderDevice.INSTANCE)) {
            return new MappedStagingBuffer(commandList);
        }

        return new FallbackStagingBuffer(commandList);
    }

    /**
     * Current logical terrain-buffer accounting. Capacities are requested GL storage sizes,
     * not measurements of physical driver residency.
     */
    public record BufferMemorySnapshot(int loadedRegionCount, int geometryArenaCount,
                                       long geometryUsedBytes, long geometryCapacityBytes,
                                       int indexArenaCount, long indexUsedBytes, long indexCapacityBytes,
                                       long terrainBufferCapacityBytes, StagingMemorySnapshot staging,
                                       ArenaRetirementSnapshot arenaRetirement) {
    }

    /** Current old-store fence/delete observations and their bounded-coverage counters. */
    public record ArenaRetirementSnapshot(int trackedRecordCount, int pendingOldCount,
                                          long pendingOldCapacityBytes, int pendingFenceCount,
                                          int deferredCleanupCount, int deleteRequestedNotReturnedCount,
                                          int deleteReturnedFencePendingCount, int deleteEventUnknownCount,
                                          int observationUnavailableCount, long coverageSkippedCount,
                                          long coverageSkippedBytes, long unsupportedFenceCount,
                                          long observedSignalCount, long observationEndedCount,
                                          long actualDeleteReturnedCount, long actualDeleteFailureCount,
                                          long backendEventOverflowCount, int backendEventQueueDepth,
                                          long lastPollShareGroup, long lastPollNanos, int lastPollCount,
                                          int lastPollDeferredCount, String lastUnsupportedReason) {
        private static ArenaRetirementSnapshot from(TerrainArenaRetirementTracker.Snapshot snapshot) {
            return new ArenaRetirementSnapshot(snapshot.trackedRecordCount(), snapshot.pendingOldCount(),
                    snapshot.pendingOldCapacityBytes(), snapshot.pendingFenceCount(), snapshot.deferredCleanupCount(),
                    snapshot.deleteRequestedNotReturnedCount(), snapshot.deleteReturnedFencePendingCount(),
                    snapshot.deleteEventUnknownCount(), snapshot.observationUnavailableCount(),
                    snapshot.coverageSkippedCount(), snapshot.coverageSkippedBytes(), snapshot.unsupportedFenceCount(),
                    snapshot.observedSignalCount(), snapshot.observationEndedCount(),
                    snapshot.actualDeleteReturnedCount(), snapshot.actualDeleteFailureCount(),
                    snapshot.backendEventOverflowCount(), snapshot.backendEventQueueDepth(),
                    snapshot.lastPollShareGroup(), snapshot.lastPollNanos(), snapshot.lastPollCount(),
                    snapshot.lastPollDeferredCount(), snapshot.lastUnsupportedReason());
        }
    }

    /**
     * Current staging queue state plus cumulative fallback upload totals. Ring in-flight bytes
     * are the sum of pending, unfenced, and fenced bytes, which represent disjoint states.
     */
    public record StagingMemorySnapshot(String type, long ringCapacityBytes, long ringAvailableBytes,
                                        long ringPendingUploadBytes, int ringPendingCopyCount,
                                        long ringUnfencedTransferredBytes, int ringFenceCount,
                                        long ringFencedBytes, long ringInFlightBytes,
                                        long fallbackLiveBytes, long fallbackUploadCountTotal,
                                        long fallbackUploadBytesTotal, boolean fallbackResetPending) {
    }
}
