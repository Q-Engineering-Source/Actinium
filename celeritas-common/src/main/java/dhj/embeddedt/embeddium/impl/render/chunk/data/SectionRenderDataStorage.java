package dhj.embeddedt.embeddium.impl.render.chunk.data;

import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.CachedBatch;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
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
}
