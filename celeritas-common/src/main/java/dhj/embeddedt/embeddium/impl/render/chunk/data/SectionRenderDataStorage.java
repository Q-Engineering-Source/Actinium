package dhj.embeddedt.embeddium.impl.render.chunk.data;

import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
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

    public record BatchCacheParams(boolean useBlockFaceCulling) {}

    // we only need to cache two batches in practice (shadow pass for shaders disables block face culling), so we unroll
    // what would otherwise be an LRU for simplicity
    private BatchCacheParams firstBatchParams;
    private CachedBatch firstCachedBatch;
    private BatchCacheParams secondBatchParams;
    private CachedBatch secondCachedBatch;

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
                firstCachedBatch.delete();
            }
            firstBatchParams = params;
            firstCachedBatch = batch;
        } else {
            if (secondCachedBatch != null) {
                secondCachedBatch.delete();
            }
            secondBatchParams = params;
            secondCachedBatch = batch;
        }
    }

    private void invalidateCachedBatches() {
        if (firstCachedBatch != null) {
            firstCachedBatch.delete();
            firstCachedBatch = null;
        }
        if (secondCachedBatch != null) {
            secondCachedBatch.delete();
            secondCachedBatch = null;
        }
        firstBatchParams = null;
        secondBatchParams = null;
    }

    public void delete() {
        this.invalidateCachedBatches();

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
