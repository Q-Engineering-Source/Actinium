package com.dhj.actinium.render.terrain.voxelization;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferArena;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.arena.PendingUpload;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.DirectMultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderVisualsService;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegionManager;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCulling;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCullingMode;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import net.coderbot.iris.celeritas.vertices.ExtendedChunkVertexType;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShadowVoxelizerGLTest extends GLStateManagerIntegrationFixture {
    private static final TerrainRenderPass SOLID = pass("voxel-solid", false);
    private static final TerrainRenderPass TRANSLUCENT = pass("voxel-translucent", true);
    private static final RenderPassConfiguration<Object> CONFIGURATION = configuration();
    private static final CameraTransform CAMERA = new CameraTransform(260.25, 100.5, -80.75);

    private CommandList commands;
    private RenderRegionManager regions;
    private GpuCullingMode previousCullingMode;

    @BeforeEach
    void setUp() {
        this.previousCullingMode = GpuCulling.mode();
        GpuCulling.setMode(GpuCullingMode.CPU_ONLY);
        RenderDevice.INSTANCE.makeActive();
        this.commands = RenderDevice.INSTANCE.createCommandList();
        this.regions = new RenderRegionManager(this.commands, CONFIGURATION);
    }

    @AfterEach
    void tearDown() {
        try {
            this.regions.delete(this.commands);
        } finally {
            this.commands.close();
            RenderDevice.INSTANCE.makeInactive();
            GpuCulling.setMode(this.previousCullingMode);
        }
    }

    @Test
    void compactRangesMatchTheTerrainBatchWithFaceCulling() {
        this.assertRangesMatch(SOLID, true);
    }

    @Test
    void compactRangesMatchTheTerrainBatchWithoutFaceCulling() {
        this.assertRangesMatch(SOLID, false);
    }

    @Test
    void fullRangesMatchSortedTerrainAndReverseRegionOrder() {
        this.assertRangesMatch(TRANSLUCENT, true);
    }

    @Test
    void refusedEncoderRestoresExistingSsboRangesAndGenericBinding() {
        this.assertBindingsRestored(true);
    }

    @Test
    void refusedEncoderRestoresWholeBufferAndUnboundSsboSlots() {
        this.assertBindingsRestored(false);
    }

    private void assertBindingsRestored(boolean ranged) {
        assumeTrue(GL.getCapabilities().OpenGL43, "SSBO bindings require OpenGL 4.3");
        RenderRegion region = this.populate(0, SOLID).getRegion();
        int vertexSlot = RwImageStoreExtractor.VG_VBUF_SSBO_BINDING;
        int rangeSlot = RwImageStoreExtractor.VG_RANGES_SSBO_BINDING;
        int alignment = GLSMQueries.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        int vertexBuffer = this.storageBuffer(alignment * 8);
        int rangeBuffer = this.storageBuffer(alignment * 8);
        int genericBuffer = this.storageBuffer(alignment * 8);
        ShadowVoxelizationSink sink = new SdlShadowVoxelizationSinkImpl();
        try {
            if (ranged) {
                GLSMBuffers.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, vertexSlot, vertexBuffer,
                    alignment, alignment * 2L);
                GLSMBuffers.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, rangeSlot, rangeBuffer,
                    alignment * 2L, alignment * 3L);
            } else {
                GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, vertexSlot, vertexBuffer);
                GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, rangeSlot, 0);
            }
            long[] vertexBinding = binding(vertexSlot);
            long[] rangeBinding = binding(rangeSlot);
            GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, genericBuffer);
            sink.region(region, 0, 0, 0);
            sink.range(0, 4);

            assertThrows(IllegalStateException.class, sink::flush,
                "the OpenGL backend refuses the SDL encoder after the sink has uploaded and bound its buffers");

            assertEquals(genericBuffer, GLSMQueries.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
            assertArrayEquals(vertexBinding, binding(vertexSlot));
            assertArrayEquals(rangeBinding, binding(rangeSlot));
        } finally {
            sink.delete();
            GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, vertexSlot, 0);
            GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, rangeSlot, 0);
            GLSMBuffers.glDeleteBuffers(vertexBuffer);
            GLSMBuffers.glDeleteBuffers(rangeBuffer);
            GLSMBuffers.glDeleteBuffers(genericBuffer);
        }
    }

    private int storageBuffer(int bytes) {
        int buffer = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_STREAM_DRAW);
        return buffer;
    }

    private static long[] binding(int index) {
        return new long[] {
            GLSMQueries.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index),
            GLSMQueries.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, index),
            GLSMQueries.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, index)
        };
    }

    private void assertRangesMatch(TerrainRenderPass pass, boolean faceCulling) {
        List<ChunkRenderList> lists = List.of(this.populate(0, pass), this.populate(8, pass));
        RangeCollector sink = new RangeCollector();
        ShadowVoxelizerImpl.walkPass(new RenderLists(lists), pass, CAMERA, CAMERA, faceCulling, sink);

        List<ChunkRenderList> ordered = pass.isReverseOrder() ? lists.reversed() : lists;
        assertEquals(ordered.size(), sink.regions.size());
        for (int index = 0; index < ordered.size(); index++) {
            ChunkRenderList list = ordered.get(index);
            RenderRegion region = list.getRegion();
            assertEquals(region, sink.regions.get(index));
            assertEquals((region.getOriginX() - CAMERA.intX) - CAMERA.fracX, sink.offsets.get(index)[0]);
            assertEquals((region.getOriginY() - CAMERA.intY) - CAMERA.fracY, sink.offsets.get(index)[1]);
            assertEquals((region.getOriginZ() - CAMERA.intZ) - CAMERA.fracZ, sink.offsets.get(index)[2]);

            SectionRenderDataStorage storage = region.getStorage(pass);
            DirectMultiDrawBatch batch = (DirectMultiDrawBatch) BatchAssembler.fillRegion(region, storage, list,
                CAMERA, pass, faceCulling && !pass.isSorted(), MultiDrawMode.DIRECT);
            assertNotNull(batch);
            try {
                Set<Integer> expected = new TreeSet<>();
                for (int draw = 0; draw < batch.size(); draw++) {
                    int start = LWJGL.memGetInt(batch.pBaseVertex + (long) draw * Integer.BYTES);
                    int count = SectionRenderDataUnsafe.verticesForElements(
                        LWJGL.memGetInt(batch.pElementCount + (long) draw * Integer.BYTES), storage.getPrimitiveType());
                    for (int vertex = start; vertex < start + count; vertex++) {
                        expected.add(vertex);
                    }
                }
                assertEquals(expected, sink.vertices.get(index));
            } finally {
                batch.delete();
            }
        }
    }

    private ChunkRenderList populate(int chunkX, TerrainRenderPass pass) {
        RenderRegion region = this.regions.createForChunk(chunkX, 0, 0);
        SectionRenderDataStorage storage = region.createStorage(pass, CONFIGURATION);
        RenderRegion.DeviceResources resources = region.createResources(this.commands);
        ChunkRenderList list = new ChunkRenderList(region);
        for (int x = 0; x < 2; x++) {
            int section = LocalSectionIndex.pack(x, 0, 0);
            Map<ModelQuadFacing, VertexRange> ranges = Map.of(
                ModelQuadFacing.POS_X, new VertexRange(0, 4),
                ModelQuadFacing.NEG_X, new VertexRange(4, 8),
                ModelQuadFacing.UNASSIGNED, new VertexRange(12, 4));
            storage.setMeshes(section, this.upload(resources.getGeometryArena(),
                16 * pass.vertexType().getVertexFormat().getStride()), null, ranges);
            list.add(section, 1 << RenderVisualsService.HAS_BLOCK_GEOMETRY);
        }
        return list;
    }

    private GlBufferSegment upload(GlBufferArena arena, int bytes) {
        NativeBuffer buffer = NativeBuffer.copy(ByteBuffer.allocateDirect(bytes));
        try {
            PendingUpload pending = PendingUpload.of(buffer);
            arena.upload(this.commands, Stream.of(pending));
            return pending.getResult();
        } finally {
            buffer.free();
        }
    }

    private static TerrainRenderPass pass(String name, boolean sorted) {
        return TerrainRenderPass.builder().name(name).useReverseOrder(sorted).useTranslucencySorting(sorted)
            .primitiveType(QuadPrimitiveType.TRIANGULATED).vertexType(new ExtendedChunkVertexType()).build();
    }

    private static RenderPassConfiguration<Object> configuration() {
        Material material = new Material(SOLID, AlphaCutoffParameter.ZERO, true);
        Map<Object, Collection<TerrainRenderPass>> stages = Map.of("voxelization", List.of(SOLID, TRANSLUCENT));
        return new RenderPassConfiguration<>(Map.of(), stages, material, material, material);
    }

    private record RenderLists(List<ChunkRenderList> lists) implements ChunkRenderListIterable {
        @Override public Iterator<ChunkRenderList> iterator(boolean reverse) {
            return (reverse ? this.lists.reversed() : this.lists).iterator();
        }
        @Override public int getNumRegions() { return this.lists.size(); }
        @Override public ChunkRenderList getRegion(int index) { return this.lists.get(index); }
        @Override public Set<TerrainRenderPass> getPasses() { return Set.of(SOLID, TRANSLUCENT); }
    }

    private static final class RangeCollector implements ShadowVoxelizationSink {
        private final List<RenderRegion> regions = new ArrayList<>();
        private final List<float[]> offsets = new ArrayList<>();
        private final List<Set<Integer>> vertices = new ArrayList<>();

        @Override public boolean region(RenderRegion region, float x, float y, float z) {
            this.regions.add(region);
            this.offsets.add(new float[] { x, y, z });
            this.vertices.add(new TreeSet<>());
            return true;
        }
        @Override public void range(int start, int count) {
            Set<Integer> regionVertices = this.vertices.getLast();
            for (int vertex = start; vertex < start + count; vertex++) {
                regionVertices.add(vertex);
            }
        }
        @Override public void flush() { }
        @Override public void discard() { }
        @Override public void delete() { }
    }
}
