package com.dhj.actinium.render.voxelization;

import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;

/**
 * Walks the visible sections of one terrain pass and reports their vertex runs, so a compute shader
 * can replay those vertices.
 *
 * <p>The walk mirrors what the multi-draw batch assembler would emit for the same pass: the same
 * visible-face mask, the same run packing and the same section order, so the voxelized vertices are
 * exactly the ones the pass draws. Everything it learns is handed to a {@link Sink}, which decides
 * what to do with the runs (the SDL GPU backend uploads them as an SSBO and dispatches one compute
 * group per region).</p>
 */
public final class ShadowVoxelizer {

    /** Receives the regions and vertex runs of one walk. */
    public interface Sink {
        /** @return false to skip this region */
        boolean region(RenderRegion region, GlVertexFormat format, float offsetX, float offsetY, float offsetZ);

        void range(int vertexOffset, int vertexCount);
    }

    public void walkPass(ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, GlVertexFormat format, CameraTransform camera, CameraTransform occlusionCamera, boolean useBlockFaceCulling, Sink sink) {
        final boolean reverse = renderPass.isReverseOrder();
        final int n = renderLists.getNumRegions();
        if (renderPass.isSorted()) {
            for (int i = 0; i < n; i++) {
                walkRegionFull(renderLists.getRegion(reverse ? n - 1 - i : i), renderPass, format, camera, sink);
            }
        } else {
            for (int i = 0; i < n; i++) {
                walkRegionCompact(renderLists.getRegion(reverse ? n - 1 - i : i), renderPass, format, camera, occlusionCamera, useBlockFaceCulling, sink);
            }
        }
    }

    private static void walkRegionCompact(ChunkRenderList list, TerrainRenderPass renderPass, GlVertexFormat format, CameraTransform camera, CameraTransform occlusionCamera, boolean useBlockFaceCulling, Sink sink) {
        final RenderRegion region = list.getRegion();
        final SectionRenderDataStorage storage = region.getStorage(renderPass);
        if (storage == null) return;

        final int sectionCount = list.getSectionsWithGeometryCount();
        if (sectionCount == 0) return;

        final byte[] sections = list.getSectionsWithGeometry();
        final long pBase = storage.getRowBasePointer();
        final long stride = SectionRenderDataUnsafe.Strategy.COMPACT.getStride();
        final ChunkPrimitiveType primitiveType = storage.getPrimitiveType();
        final int regionChunkX = region.getChunkX();
        final int regionChunkY = region.getChunkY();
        final int regionChunkZ = region.getChunkZ();
        boolean bound = false;

        for (int i = 0; i < sectionCount; i++) {
            final int sectionIndex = sections[i] & 0xFF;
            int slices = useBlockFaceCulling
                ? BatchAssembler.getVisibleFaces(occlusionCamera.intX, occlusionCamera.intY, occlusionCamera.intZ,
                    regionChunkX + LocalSectionIndex.unpackX(sectionIndex),
                    regionChunkY + LocalSectionIndex.unpackY(sectionIndex),
                    regionChunkZ + LocalSectionIndex.unpackZ(sectionIndex))
                : ModelQuadFacing.ALL;
            slices &= storage.getSliceMask(sectionIndex);
            slices &= 0x7F;
            if (slices == 0) continue;

            final long pMeshData = pBase + sectionIndex * stride;
            final long runs = BatchAssembler.packRuns(slices);
            final int runCount = BatchAssembler.runCount(runs);
            for (int run = 0; run < runCount; run++) {
                final int startVertex = SectionRenderDataUnsafe.Strategy.COMPACT.getVertexOffset(pMeshData, BatchAssembler.runFirst(runs, run));
                final int endVertex = SectionRenderDataUnsafe.Strategy.COMPACT.getRunVertexEnd(pMeshData, BatchAssembler.runLast(runs, run), primitiveType);
                final int vertexCount = endVertex - startVertex;
                if (vertexCount <= 0) continue;

                if (!bound) {
                    if (!bindRegion(region, format, camera, sink)) return;
                    bound = true;
                }
                sink.range(startVertex, vertexCount);
            }
        }
    }

    private static void walkRegionFull(ChunkRenderList list, TerrainRenderPass renderPass, GlVertexFormat format, CameraTransform camera, Sink sink) {
        final RenderRegion region = list.getRegion();
        final SectionRenderDataStorage storage = region.getStorage(renderPass);
        if (storage == null) return;

        final int sectionCount = list.getSectionsWithGeometryCount();
        if (sectionCount == 0) return;

        final byte[] sections = list.getSectionsWithGeometry();
        final long pBase = storage.getRowBasePointer();
        final long stride = SectionRenderDataUnsafe.Strategy.FULL.getStride();
        final ChunkPrimitiveType primitiveType = storage.getPrimitiveType();
        boolean bound = false;

        for (int i = 0; i < sectionCount; i++) {
            final int sectionIndex = sections[i] & 0xFF;
            final int slices = ModelQuadFacing.ALL & storage.getSliceMask(sectionIndex);
            if (slices == 0) continue;

            final long pMeshData = pBase + sectionIndex * stride;
            final long runs = BatchAssembler.packRuns(slices);
            final int runCount = BatchAssembler.runCount(runs);
            for (int run = 0; run < runCount; run++) {
                final int startVertex = SectionRenderDataUnsafe.Strategy.FULL.getVertexOffset(pMeshData, BatchAssembler.runFirst(runs, run));
                final int endVertex = SectionRenderDataUnsafe.Strategy.FULL.getRunVertexEnd(pMeshData, BatchAssembler.runLast(runs, run), primitiveType);
                final int vertexCount = endVertex - startVertex;
                if (vertexCount <= 0) continue;

                if (!bound) {
                    if (!bindRegion(region, format, camera, sink)) return;
                    bound = true;
                }
                sink.range(startVertex, vertexCount);
            }
        }
    }

    private static boolean bindRegion(RenderRegion region, GlVertexFormat format, CameraTransform camera, Sink sink) {
        return sink.region(region, format,
            cameraRelative(region.getOriginX(), camera.intX, camera.fracX),
            cameraRelative(region.getOriginY(), camera.intY, camera.fracY),
            cameraRelative(region.getOriginZ(), camera.intZ, camera.fracZ));
    }

    private static float cameraRelative(int originBlock, int cameraBlock, float cameraFrac) {
        return (originBlock - cameraBlock) - cameraFrac;
    }
}
