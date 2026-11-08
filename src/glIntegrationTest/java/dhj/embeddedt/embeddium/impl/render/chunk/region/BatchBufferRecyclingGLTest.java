package dhj.embeddedt.embeddium.impl.render.chunk.region;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.DirectMultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.IndirectMultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatchFactory;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.chunk.DefaultChunkRenderer;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderVisualsService;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.CachedBatch;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL40;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the region batch cache through a real GL context. A rebuilt batch must upload into the buffer of the batch
 * it replaces instead of generating a new name, the held-back buffer must not outlive its storage, the two views
 * must not take each other's batch, and the culling flag of the cache key must decide how far the camera can move
 * before the batch is stale.
 *
 * <p>The section lists name sections without meshes, so the assembled batches carry no commands. The upload still
 * binds and re-specifies the indirect buffer, which is the state these tests observe. A cache hit returns the
 * previously stored {@link CachedBatch}, so the tests compare batch identity to tell a rebuild from a cache hit.
 */
class BatchBufferRecyclingGLTest extends GLStateManagerIntegrationFixture {
    private static final int BLOCK_GEOMETRY_FLAG = 1 << RenderVisualsService.HAS_BLOCK_GEOMETRY;
    private static final CameraTransform CAMERA = new CameraTransform(0, 0, 0);
    private static final CameraTransform FAR_CAMERA = new CameraTransform(1000, 1000, 1000);

    private CommandList commandList;
    private SectionRenderDataStorage storage;
    private RenderRegion region;
    private ChunkRenderList renderList;
    private TerrainRenderPass pass;
    private TestRenderer renderer;
    private MultiDrawBatchFactory previousBatchFactory;

    @BeforeEach
    void setUp() {
        this.previousBatchFactory = BatchAssembler.getBatchFactory();
        BatchAssembler.setBatchFactory(IndirectMultiDrawBatch::new);

        RenderDevice.INSTANCE.makeActive();
        this.commandList = RenderDevice.INSTANCE.createCommandList();

        // Known baseline: an indirect upload must not leave an array buffer bound behind it.
        GL15C.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        this.region = RenderRegionTestFactory.create(0, 0, 0, 0,
                new CompactChunkVertex().getVertexFormat().getStride());
        // The fixture feeds the storage no arena segments, so one arena element is one vertex.
        this.storage = new SectionRenderDataStorage(QuadPrimitiveType.TRIANGULATED, false, 1);
        this.renderList = new ChunkRenderList(this.region);
        this.renderList.add(0, BLOCK_GEOMETRY_FLAG);
        this.pass = TerrainRenderPass.builder()
                .name("solid")
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(new CompactChunkVertex())
                .build();
        this.renderer = new TestRenderer();
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.renderer != null) {
                this.renderer.delete(this.commandList);
            }
        } finally {
            try {
                if (this.storage != null) {
                    this.storage.delete();
                }
            } finally {
                try {
                    RenderDevice.INSTANCE.makeInactive();
                } finally {
                    BatchAssembler.setBatchFactory(this.previousBatchFactory);
                }
            }
        }
    }

    @Test
    void invalidatedBatchHandsItsBufferToTheRebuild() {
        final CachedBatch first = this.build(this.renderList);
        final int retired = this.boundIndirectBuffer();
        assertNotEquals(0, retired, "the first build must upload a buffer");

        this.storage.invalidateCachedBatches();
        assertTrue(GL15C.glIsBuffer(retired), "an invalidated batch keeps its buffer for the rebuild");

        final CachedBatch rebuilt = this.build(this.renderList);
        assertNotSame(first, rebuilt, "the invalidated batch is rebuilt");
        assertEquals(retired, this.boundIndirectBuffer(), "the rebuild uploads into the retired buffer");
    }

    @Test
    void staleBatchIsRetiredBeforeTheRebuildUploads() {
        final CachedBatch first = this.build(this.renderList);
        final int stale = this.boundIndirectBuffer();
        assertNotEquals(0, stale, "the first build must upload a buffer");

        this.renderList.add(1, BLOCK_GEOMETRY_FLAG);
        final CachedBatch rebuilt = this.build(this.renderList);

        assertNotSame(first, rebuilt, "the changed section list rebuilds the batch");
        assertEquals(stale, this.boundIndirectBuffer(), "the stale batch hands its buffer to the rebuild");
        assertTrue(GL15C.glIsBuffer(stale), "the rebuilt batch owns the buffer the stale batch released");
    }

    @Test
    void deletingTheStorageDeletesTheSpareBuffer() {
        this.build(this.renderList);
        final int spare = this.boundIndirectBuffer();
        assertNotEquals(0, spare, "the build must upload a buffer");

        this.storage.invalidateCachedBatches();
        assertTrue(GL15C.glIsBuffer(spare), "invalidation holds the buffer for reuse");

        this.storage.delete();
        this.storage = null;

        assertFalse(GL15C.glIsBuffer(spare), "the held-back buffer must not outlive its storage");
    }

    @Test
    void viewsKeepTheirOwnBatchWhenTheyAlternate() {
        final ChunkRenderList otherViewList = this.otherViewList();

        this.renderer.useView(0);
        final CachedBatch firstViewBatch = this.build(this.renderList);
        final int firstViewBuffer = this.boundIndirectBuffer();
        assertNotEquals(0, firstViewBuffer, "the first view must upload a buffer");

        this.renderer.useView(1);
        final CachedBatch secondViewBatch = this.build(otherViewList);
        final int secondViewBuffer = this.boundIndirectBuffer();
        assertNotEquals(0, secondViewBuffer, "the second view must upload a buffer");
        assertNotEquals(firstViewBuffer, secondViewBuffer, "the second view builds a batch of its own");

        for (int round = 0; round < 4; round++) {
            this.renderer.useView(0);
            assertSame(firstViewBatch, this.build(this.renderList), "view 0 is served from its cached batch");
            assertEquals(secondViewBuffer, this.boundIndirectBuffer(), "a cache hit binds no other buffer");

            this.renderer.useView(1);
            assertSame(secondViewBatch, this.build(otherViewList), "view 1 is served from its cached batch");
            assertEquals(secondViewBuffer, this.boundIndirectBuffer(), "a cache hit binds no other buffer");
        }

        assertTrue(GL15C.glIsBuffer(firstViewBuffer), "view 0 keeps its buffer across the alternation");
        assertTrue(GL15C.glIsBuffer(secondViewBuffer), "view 1 keeps its buffer across the alternation");

        // A changed section list retires view 0's batch, so its rebuild has to reuse that batch's own buffer.
        this.renderList.add(1, BLOCK_GEOMETRY_FLAG);
        this.renderer.useView(0);
        final CachedBatch rebuilt = this.build(this.renderList);

        assertNotSame(firstViewBatch, rebuilt, "the changed section list rebuilds view 0");
        assertEquals(firstViewBuffer, this.boundIndirectBuffer(), "the view 0 rebuild reuses the view 0 buffer");
        assertTrue(GL15C.glIsBuffer(secondViewBuffer), "the view 1 batch survives the view 0 rebuild");
    }

    @Test
    void theCullingFlagOfTheKeyDecidesTheCameraValidityInterval() {
        final CachedBatch unculled = this.build(this.renderList, CAMERA, false);
        assertSame(unculled, this.build(this.renderList, FAR_CAMERA, false),
                "a batch built without block face culling stays valid however far the camera is");

        this.storage.invalidateCachedBatches();

        final CachedBatch culled = this.build(this.renderList, CAMERA, true);
        assertNotSame(culled, this.build(this.renderList, FAR_CAMERA, true),
                "a batch built with block face culling is rebuilt once the camera leaves its interval");
    }

    @Test
    void theDefaultDirectBatchKeepsNoGpuBuffer() {
        final CachedBatch indirect = this.build(this.renderList);
        final int spare = this.boundIndirectBuffer();
        assertNotEquals(0, spare, "the indirect build uploads a buffer");

        this.storage.invalidateCachedBatches();
        BatchAssembler.setBatchFactory(null);

        final CachedBatch direct = this.build(this.renderList);
        assertNotSame(indirect, direct, "the invalidated batch is rebuilt");
        assertDirectWithoutBuffer(direct, "the default direct mode");

        this.storage.invalidateCachedBatches();
        final CachedBatch rebuilt = this.build(this.renderList);
        assertNotSame(direct, rebuilt, "the invalidated direct batch is rebuilt");
        assertDirectWithoutBuffer(rebuilt, "the rebuilt direct batch");
        assertTrue(GL15C.glIsBuffer(spare), "the spare buffer stays with the storage");

        this.storage.delete();
        this.storage = null;

        assertFalse(GL15C.glIsBuffer(spare), "the storage still held the spare and frees it on delete");
    }

    private static void assertDirectWithoutBuffer(CachedBatch cached, String subject) {
        final MultiDrawBatch batch = cached.getBatch();
        assertInstanceOf(DirectMultiDrawBatch.class, batch, subject + " assembles a direct batch");
        assertFalse(batch.usesGpuBuffer(), subject + " keeps its commands in CPU memory");
        assertNull(cached.detachBuffer(), subject + " takes over no GL buffer");
    }

    private CachedBatch build(ChunkRenderList list) {
        return this.build(list, CAMERA, false);
    }

    private CachedBatch build(ChunkRenderList list, CameraTransform camera, boolean useBlockFaceCulling) {
        final CachedBatch cached = this.renderer.build(this.commandList, this.region, this.storage, list, this.pass,
                camera, useBlockFaceCulling);
        assertNotNull(cached, "the renderer returns a batch for every region");
        assertEquals(0, GL11C.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                "the indirect upload must leave the array buffer target untouched");
        return cached;
    }

    private int boundIndirectBuffer() {
        return GL11C.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
    }

    private ChunkRenderList otherViewList() {
        final ChunkRenderList list = new ChunkRenderList(this.region);
        list.add(0, BLOCK_GEOMETRY_FLAG);
        list.add(1, BLOCK_GEOMETRY_FLAG);
        return list;
    }

    private static final class TestRenderer extends DefaultChunkRenderer {
        private int view;

        private TestRenderer() {
            super(RenderDevice.INSTANCE, null, null);
        }

        private void useView(int view) {
            this.view = view;
        }

        @Override
        protected SectionRenderDataStorage.BatchCacheParams batchCacheParams(boolean useBlockFaceCulling) {
            return SectionRenderDataStorage.BatchCacheParams.of(useBlockFaceCulling, this.view);
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
        }

        private CachedBatch build(CommandList commandList, RenderRegion region, SectionRenderDataStorage storage,
                                  ChunkRenderList list, TerrainRenderPass renderPass, CameraTransform camera,
                                  boolean useBlockFaceCulling) {
            return this.getRegionBatch(commandList, region, storage, list, camera, renderPass,
                    this.batchCacheParams(useBlockFaceCulling));
        }
    }
}
