package dhj.embeddedt.embeddium.impl.render.chunk.data;

import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.CachedBatch;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCulling;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;

public class SectionRenderDataStorage {
    private final GlBufferSegment[] allocations = new GlBufferSegment[RenderRegion.REGION_SIZE];
    private final GlBufferSegment[] indexAllocations = new GlBufferSegment[RenderRegion.REGION_SIZE];

    private final long pMeshDataArray;
    private final ChunkPrimitiveType primitiveType;
    private final SectionRenderDataUnsafe.Strategy storageStrategy;

    private int numAllocations;

    /**
     * Region and pass index tag this storage for the compute cull backend, and are installed by
     * {@link RenderRegion#createStorage}. Both stay unset for a storage that no region owns, which keeps such a
     * storage out of the section mirror entirely.
     */
    private @Nullable RenderRegion region;
    private int passIndex = -1;

    /**
     * Slot of every section in the section mirror, indexed by local section index. Written by whoever rebuilds a
     * section and read by the cull walk on the render thread, hence the volatile array reference. A {@code -1} entry
     * means the section has no mirror slot.
     */
    private volatile int[] slotCache;

    /**
     * Whether this storage serves the compute cull path, decided once when the region attaches it. The availability
     * probe reads the configured multidraw mode, and a renderer that reloads builds fresh storages, so evaluating it
     * at attach time keeps the per-section upload path free of driver queries.
     */
    private boolean publishesSectionMeta;

    /**
     * Cache key of one batch slot: the culling mode plus the view it is drawn from.
     *
     * @param useBlockFaceCulling whether the batch was built with block face culling
     * @param view               which view draws the batch; views 0 and 1 share pre-built keys because they are
     *                           drawn every frame and would otherwise allocate one key per region
     */
    public record BatchCacheParams(boolean useBlockFaceCulling, int view) {
        private static final BatchCacheParams[] SHARED = {
                new BatchCacheParams(false, 0), new BatchCacheParams(true, 0),
                new BatchCacheParams(false, 1), new BatchCacheParams(true, 1)
        };

        public static BatchCacheParams of(boolean useBlockFaceCulling, int view) {
            if (view == 0 || view == 1) {
                return SHARED[view * 2 + (useBlockFaceCulling ? 1 : 0)];
            }
            return new BatchCacheParams(useBlockFaceCulling, view);
        }
    }

    // Two slots cover the batches drawn every frame: a key is the (culling, view) pair, so the main view and the
    // shadow view, whose passes differ in block face culling, fit side by side. Any further key replaces the key
    // stored in the second slot, so a third key in use keeps evicting the previous second-slot batch until the next
    // mesh update invalidates both slots.
    private BatchCacheParams firstBatchParams;
    private CachedBatch firstCachedBatch;
    private BatchCacheParams secondBatchParams;
    private CachedBatch secondCachedBatch;

    /**
     * Holds the GL buffers of discarded batches until the rebuild for the same slot takes them over, so a rebuilt
     * batch uploads into a buffer that already has the right name and layout.
     */
    private final BatchBufferRecycler<GlMutableBuffer> batchBufferRecycler = new BatchBufferRecycler<>();

    /**
     * Creates FULL metadata for compatibility with the existing Actinium render-region construction path.
     */
    public SectionRenderDataStorage(ChunkPrimitiveType primitiveType) {
        this(primitiveType, true);
    }

    /**
     * Creates FULL metadata for sorted passes and COMPACT metadata for unsorted passes.
     */
    public SectionRenderDataStorage(ChunkPrimitiveType primitiveType, boolean sorted) {
        this.storageStrategy = sorted ? SectionRenderDataUnsafe.Strategy.FULL : SectionRenderDataUnsafe.Strategy.COMPACT;
        this.pMeshDataArray = this.storageStrategy.allocateHeap();
        if (this.pMeshDataArray == 0) {
            throw new OutOfMemoryError("Failed to allocate mesh data array");
        }
        this.primitiveType = primitiveType;
    }

    public boolean isEmpty() {
        return this.numAllocations == 0;
    }

    /**
     * {@return the primitive type used to derive COMPACT element counts and to rebase metadata}
     */
    public ChunkPrimitiveType getPrimitiveType() {
        return this.primitiveType;
    }

    public SectionRenderDataUnsafe.Strategy getStrategy() {
        return this.storageStrategy;
    }

    /**
     * Attaches the storage to the region that owns it, which is what allows its sections to be published to the
     * section mirror. {@link RenderRegion#createStorage} is the only caller: it attaches each storage exactly once,
     * right after construction, which is also the point where the publish gate is decided.
     *
     * @param region    the owning region, whose origin addresses the storage's sections
     * @param passIndex the stable index of the render pass this storage holds, see {@code RenderPassIndex}
     */
    public void attachRegion(RenderRegion region, int passIndex) {
        if (this.region != null) {
            throw new IllegalStateException("Storage is already attached to region " + this.region.getId());
        }
        this.region = region;
        this.passIndex = passIndex;
        this.publishesSectionMeta = GpuCulling.mode().computeEnabled() && GpuCulling.isAvailable();
    }

    /**
     * {@return the region whose sections this storage holds, or {@code null} while the storage is unattached}
     */
    public @Nullable RenderRegion getRegion() {
        return this.region;
    }

    /**
     * {@return the stable index of this storage's render pass, or {@code -1} while the storage is unattached}
     */
    public int getPassIndex() {
        return this.passIndex;
    }

    /**
     * {@return the slot of every section indexed by local section index, or {@code null} while no section has been
     * published to the section mirror}
     */
    public int @Nullable [] getSlotCache() {
        return this.slotCache;
    }

    public void setMeshes(int localSectionIndex,
                          GlBufferSegment allocation, @Nullable GlBufferSegment indexAllocation,
                          Map<ModelQuadFacing, VertexRange> ranges) {
        if (this.allocations[localSectionIndex] != null) {
            this.allocations[localSectionIndex].delete();
            this.allocations[localSectionIndex] = null;
            this.numAllocations--;
        }

        if (this.indexAllocations[localSectionIndex] != null) {
            this.indexAllocations[localSectionIndex].delete();
            this.indexAllocations[localSectionIndex] = null;
        }

        this.allocations[localSectionIndex] = allocation;
        this.indexAllocations[localSectionIndex] = indexAllocation;
        this.numAllocations++;

        int vertexOffset = allocation.getOffset();
        int indexOffset = indexAllocation != null ? indexAllocation.getOffset() * 4 : 0;

        this.storageStrategy.writeMeshesAndSliceMask(this.pMeshDataArray, localSectionIndex,
                vertexOffset, indexOffset, ranges, this.primitiveType);

        this.invalidateCachedBatches();
        this.pushSectionMeta(localSectionIndex);
    }

    public void removeMeshes(int localSectionIndex) {
        if (this.allocations[localSectionIndex] != null) {
            this.allocations[localSectionIndex].delete();
            this.allocations[localSectionIndex] = null;
            this.numAllocations--;

            this.invalidateCachedBatches();
        }

        this.storageStrategy.clearRow(this.pMeshDataArray, localSectionIndex);
        removeIndexBuffer(localSectionIndex);
        this.removeSectionMeta(localSectionIndex);
    }

    public void removeIndexBuffer(int localSectionIndex) {
        if (this.indexAllocations[localSectionIndex] != null) {
            this.indexAllocations[localSectionIndex].delete();
            this.indexAllocations[localSectionIndex] = null;

            this.invalidateCachedBatches();
        }
    }

    public void replaceIndexBuffer(int localSectionIndex, GlBufferSegment indexAllocation) {
        if (this.storageStrategy == SectionRenderDataUnsafe.Strategy.COMPACT) {
            throw new UnsupportedOperationException("COMPACT metadata does not support index buffer replacement");
        }
        if (indexAllocation == null) {
            throw new IllegalArgumentException("indexAllocation must not be null");
        }

        removeIndexBuffer(localSectionIndex);

        this.indexAllocations[localSectionIndex] = indexAllocation;

        var pMeshData = this.getDataPointer(localSectionIndex);
        int indexOffset = indexAllocation.getOffset() * 4;

        this.storageStrategy.writeIndexOffsets(pMeshData, indexOffset, this.primitiveType);

        this.invalidateCachedBatches();
        this.pushSectionMeta(localSectionIndex);
    }

    public void onBufferResized() {
        for (int sectionIndex = 0; sectionIndex < RenderRegion.REGION_SIZE; sectionIndex++) {
            this.updateMeshes(sectionIndex);
        }

        this.invalidateCachedBatches();
    }

    private void updateMeshes(int sectionIndex) {
        var allocation = this.allocations[sectionIndex];

        if (allocation == null) {
            return;
        }

        var indexAllocation = this.indexAllocations[sectionIndex];
        int vertexOffset = allocation.getOffset();
        int indexOffset = indexAllocation != null ? indexAllocation.getOffset() * 4 : 0;

        this.storageStrategy.rebase(this.getDataPointer(sectionIndex), vertexOffset, indexOffset, this.primitiveType);
        this.pushSectionMeta(sectionIndex);
    }

    /**
     * {@return the address of one section's metadata row}
     */
    public long getDataPointer(int sectionIndex) {
        return this.storageStrategy.heapPointer(this.pMeshDataArray, sectionIndex);
    }

    /**
     * {@return the address of the first metadata row, after any strategy-specific header}
     */
    public long getRowBasePointer() {
        return this.storageStrategy.getRowBasePointer(this.pMeshDataArray);
    }

    /**
     * {@return the slice mask for one section}
     */
    public int getSliceMask(int sectionIndex) {
        return this.storageStrategy.getSliceMask(this.pMeshDataArray, sectionIndex);
    }

    /**
     * Publishes one section's row to the section mirror and records its slot. A section whose slice mask became empty
     * has nothing to draw, so its slot is released instead of being uploaded.
     *
     * <p>Rows are only written while the compute cull path is both selected and available, and availability is the
     * value this storage was attached with, so the upload path never probes the driver. Releasing a slot stays
     * unconditional, so a storage that published while the compute path was active still gives its slots back after
     * the mode or the context changed.
     */
    private void pushSectionMeta(int localSectionIndex) {
        int sliceMask = this.getSliceMask(localSectionIndex);

        if (sliceMask == 0) {
            this.removeSectionMeta(localSectionIndex);
            return;
        }

        if (!this.publishesSectionMeta || !GpuCulling.mode().computeEnabled()) {
            // The rebuild dropped the section's previous vertex range, so a row published for it no longer describes
            // the storage and has to go as well.
            this.removeSectionMeta(localSectionIndex);
            return;
        }

        RenderRegion region = this.region;

        if (region == null) {
            return;
        }

        int slot = GpuCulling.sectionMeta()
                .update(this.passIndex, region.getOriginX(), region.getOriginY(), region.getOriginZ(),
                        localSectionIndex, this.getDataPointer(localSectionIndex), sliceMask, this.storageStrategy,
                        this.primitiveType);

        if (slot < 0) {
            return;
        }

        int[] cache = this.slotCache;

        if (cache == null) {
            cache = new int[RenderRegion.REGION_SIZE];
            Arrays.fill(cache, -1);
            this.slotCache = cache;
        }

        cache[localSectionIndex] = slot;
    }

    /**
     * Releases one section's mirror slot, if it holds one.
     */
    private void removeSectionMeta(int localSectionIndex) {
        RenderRegion region = this.region;

        if (region == null) {
            return;
        }

        int[] cache = this.slotCache;

        if (cache != null) {
            cache[localSectionIndex] = -1;
        }

        GpuCulling.sectionMeta()
                .remove(this.passIndex, region.getOriginX(), region.getOriginY(), region.getOriginZ(),
                        localSectionIndex);
    }

    public @Nullable CachedBatch getCachedMultiDrawBatch(BatchCacheParams params) {
        if (params.equals(firstBatchParams)) {
            return firstCachedBatch;
        } else if (params.equals(secondBatchParams)) {
            return secondCachedBatch;
        } else {
            return null;
        }
    }

    public void storeCachedMultiDrawBatch(BatchCacheParams params, CachedBatch batch) {
        if (params.equals(firstBatchParams) || firstBatchParams == null) {
            if (firstCachedBatch != null) {
                this.discardCachedBatch(firstCachedBatch);
            }
            firstBatchParams = params;
            firstCachedBatch = batch;
        } else {
            if (secondCachedBatch != null) {
                this.discardCachedBatch(secondCachedBatch);
            }
            secondBatchParams = params;
            secondCachedBatch = batch;
        }
    }

    public void invalidateCachedBatches() {
        if (firstCachedBatch != null) {
            this.discardCachedBatch(firstCachedBatch);
            firstCachedBatch = null;
        }
        if (secondCachedBatch != null) {
            this.discardCachedBatch(secondCachedBatch);
            secondCachedBatch = null;
        }
        firstBatchParams = null;
        secondBatchParams = null;
    }

    /**
     * {@return the buffer of a discarded batch for a new batch to upload into, or {@code null} when none is held}
     */
    public @Nullable GlMutableBuffer takeSpareBatchBuffer() {
        return this.batchBufferRecycler.take();
    }

    /**
     * Discards the cached batch of {@code params} ahead of its rebuild so that the new batch can take over its
     * buffer. The params stay in place, so {@link #storeCachedMultiDrawBatch} puts the new batch in the same slot.
     */
    public void retireCachedMultiDrawBatch(BatchCacheParams params) {
        if (params.equals(firstBatchParams)) {
            if (firstCachedBatch != null) {
                this.discardCachedBatch(firstCachedBatch);
                firstCachedBatch = null;
            }
        } else if (params.equals(secondBatchParams)) {
            if (secondCachedBatch != null) {
                this.discardCachedBatch(secondCachedBatch);
                secondCachedBatch = null;
            }
        }
    }

    /**
     * Deletes a batch while keeping its GL buffer alive for the next batch in the same slot.
     */
    private void discardCachedBatch(CachedBatch batch) {
        this.batchBufferRecycler.retire(batch.detachBuffer(), GlMutableBuffer::delete);
        batch.delete();
    }

    public void delete() {
        this.invalidateCachedBatches();
        this.batchBufferRecycler.drain(GlMutableBuffer::delete);

        this.releaseAllSectionMeta();

        for (var allocation : this.allocations) {
            if (allocation != null) {
                allocation.delete();
            }
        }

        for (var allocation : this.indexAllocations) {
            if (allocation != null) {
                allocation.delete();
            }
        }

        Arrays.fill(this.allocations, null);
        Arrays.fill(this.indexAllocations, null);

        this.storageStrategy.freeHeap(this.pMeshDataArray);

        this.numAllocations = 0;
    }

    /**
     * Retires every mirror slot this storage still holds.
     */
    private void releaseAllSectionMeta() {
        int[] cache = this.slotCache;
        RenderRegion region = this.region;

        if (cache == null || region == null) {
            return;
        }

        for (int localSectionIndex = 0; localSectionIndex < cache.length; localSectionIndex++) {
            if (cache[localSectionIndex] < 0) {
                continue;
            }

            cache[localSectionIndex] = -1;
            GpuCulling.sectionMeta()
                    .remove(this.passIndex, region.getOriginX(), region.getOriginY(), region.getOriginZ(),
                            localSectionIndex);
        }
    }
}
