package com.dhj.actinium.render.terrain.voxelization;

import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import net.coderbot.iris.Iris;
import net.coderbot.iris.celeritas.vertices.ExtendedChunkVertexType;
import net.coderbot.iris.gl.program.ComputeProgram;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import org.lwjgl.opengl.GL20;

/** Replays the same section face ranges the terrain renderer uses, before rasterization starts. */
final class ShadowVoxelizerImpl implements ShadowVoxelizer {
    private static final GlVertexFormat COMPUTE_VERTEX_FORMAT = new ExtendedChunkVertexType().getVertexFormat();
    private final ShadowVoxelizationSink sink = new SdlShadowVoxelizationSinkImpl();

    @Override
    public void voxelize(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
                         TerrainRenderPass renderPass, GlVertexFormat format, CameraTransform occlusionCamera,
                         CameraTransform camera, boolean useBlockFaceCulling) {
        if (!BackendManager.RENDER_BACKEND.isSDLGPU() || !ShaderProviderHolder.isShadowPass()
            || !renderLists.hasPass(renderPass)) {
            return;
        }
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof DeferredWorldRenderingPipeline pipeline)) {
            return;
        }
        ComputeProgram compute = pipeline.getShadowVoxelizationCompute();
        if (compute == null) {
            return;
        }
        requireComputeVertexFormat(format);
        int previousProgram = GLSMQueries.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            compute.use();
            pipeline.prepareShadowVoxelizationCompute(matrices.modelView());
            walkPass(renderLists, renderPass, occlusionCamera, camera, useBlockFaceCulling, this.sink);
            this.sink.flush();
        } finally {
            this.sink.discard();
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
            GLSMPrograms.glUseProgram(previousProgram);
        }
    }

    static void requireComputeVertexFormat(GlVertexFormat format) {
        if (format.getStride() != COMPUTE_VERTEX_FORMAT.getStride()) {
            throw new IllegalStateException("Shadow voxelization requires the complete terrain vertex format: " + format);
        }
        for (GlVertexAttribute expected : COMPUTE_VERTEX_FORMAT.getAttributes()) {
            GlVertexAttribute actual = format.getAttribute(expected.getName());
            if (actual.getPointer() != expected.getPointer() || actual.getFormat() != expected.getFormat()
                || actual.getCount() != expected.getCount() || actual.isNormalized() != expected.isNormalized()
                || actual.isIntType() != expected.isIntType()) {
                throw new IllegalStateException("Shadow voxelization vertex attribute differs from its compute layout: "
                    + expected.getName());
            }
        }
    }

    static void walkPass(ChunkRenderListIterable lists, TerrainRenderPass pass, CameraTransform occlusionCamera,
                         CameraTransform camera, boolean useBlockFaceCulling, ShadowVoxelizationSink sink) {
        int regions = lists.getNumRegions();
        for (int index = 0; index < regions; index++) {
            ChunkRenderList list = lists.getRegion(pass.isReverseOrder() ? regions - index - 1 : index);
            walkRegion(list, pass, occlusionCamera, camera, useBlockFaceCulling && !pass.isSorted(), sink);
        }
    }

    private static void walkRegion(ChunkRenderList list, TerrainRenderPass pass, CameraTransform occlusionCamera,
                                   CameraTransform camera, boolean useBlockFaceCulling, ShadowVoxelizationSink sink) {
        RenderRegion region = list.getRegion();
        SectionRenderDataStorage storage = region.getStorage(pass);
        if (storage == null) {
            return;
        }
        SectionRenderDataUnsafe.Strategy strategy = storage.getStrategy();
        byte[] sections = list.getSectionsWithGeometry();
        boolean bound = false;
        for (int index = 0; index < list.getSectionsWithGeometryCount(); index++) {
            int section = sections[index] & 0xFF;
            int slices = useBlockFaceCulling
                ? BatchAssembler.getVisibleFaces(occlusionCamera.intX, occlusionCamera.intY, occlusionCamera.intZ,
                    region.getChunkX() + LocalSectionIndex.unpackX(section),
                    region.getChunkY() + LocalSectionIndex.unpackY(section),
                    region.getChunkZ() + LocalSectionIndex.unpackZ(section))
                : ModelQuadFacing.ALL;
            slices &= storage.getSliceMask(section);
            long row = storage.getDataPointer(section);
            long runs = BatchAssembler.packRuns(slices);
            for (int run = 0; run < BatchAssembler.runCount(runs); run++) {
                int start = strategy.getVertexOffset(row, BatchAssembler.runFirst(runs, run));
                int end = strategy.getRunVertexEnd(row, BatchAssembler.runLast(runs, run), storage.getPrimitiveType());
                if (end <= start) {
                    continue;
                }
                if (!bound) {
                    if (!sink.region(region,
                        (region.getOriginX() - camera.intX) - camera.fracX,
                        (region.getOriginY() - camera.intY) - camera.fracY,
                        (region.getOriginZ() - camera.intZ) - camera.fracZ)) {
                        return;
                    }
                    bound = true;
                }
                sink.range(start, end - start);
            }
        }
    }

    @Override
    public void delete() {
        this.sink.delete();
    }
}
