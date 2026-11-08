package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferArena;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.arena.PendingUpload;
import dhj.embeddedt.embeddium.impl.gl.array.GlVertexArray;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeBinding;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlVertexArrayTessellation;
import dhj.embeddedt.embeddium.impl.gl.tessellation.TessellationBinding;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderVisualsService;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.CachedBatch;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegionManager;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.VanillaLikeChunkVertex;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Drives the geometry arena of a region through a real GL context. Every pass of a region allocates from the one arena,
 * whatever vertex format it uses, and the arena allocates in elements that hold whole primitives, so a shader can
 * derive a vertex's corner from {@code gl_VertexID}. The caches that hang off those buffers, the tessellation of each
 * pass and the assembled batches of each pass, are dropped when the buffer they bind goes away.
 */
class SharedGeometryArenaGLTest extends GLStateManagerIntegrationFixture {
    private static final ChunkPrimitiveType PRIMITIVE_TYPE = QuadPrimitiveType.TRIANGULATED;
    private static final int BLOCK_GEOMETRY_FLAG = 1 << RenderVisualsService.HAS_BLOCK_GEOMETRY;
    private static final CameraTransform CAMERA = new CameraTransform(0, 0, 0);

    /**
     * Grows the arena past the size where it switches to the smaller growth factor. The relocated segments then start
     * at a capacity that is not a multiple of the primitives their formats are built from, which is the case a
     * primitive-aligned arena element has to absorb.
     */
    private static final int LARGE_GROWTH_BYTES = 5 * 1024 * 1024;

    private static final TerrainRenderPass COMPACT_PASS = pass("shared-arena-compact", new CompactChunkVertex());
    private static final TerrainRenderPass VANILLA_LIKE_PASS =
            pass("shared-arena-vanilla-like", new VanillaLikeChunkVertex());
    /** Shares its tessellation key with {@link #COMPACT_PASS}: same vertex format, primitive type and index layout. */
    private static final TerrainRenderPass SECOND_COMPACT_PASS =
            pass("shared-arena-compact-second", new CompactChunkVertex());
    private static final RenderPassConfiguration<Object> CONFIGURATION = configuration();

    private CommandList commandList;
    private RenderRegionManager regionManager;
    private RenderRegion region;
    private GpuCullingMode previousCullingMode;

    @BeforeEach
    void setUp() {
        // The fixtures publish no sections, so they keep the CPU emitter and never probe the compute backend.
        this.previousCullingMode = GpuCulling.mode();
        GpuCulling.setMode(GpuCullingMode.CPU_ONLY);
        RenderDevice.INSTANCE.makeActive();
        this.commandList = RenderDevice.INSTANCE.createCommandList();
        this.regionManager = new RenderRegionManager(this.commandList, CONFIGURATION);
        this.region = this.regionManager.createForChunk(0, 0, 0);
    }

    @AfterEach
    void tearDown() {
        try {
            this.regionManager.delete(this.commandList);
        } finally {
            try {
                this.commandList.close();
                RenderDevice.INSTANCE.makeInactive();
            } finally {
                GpuCulling.setMode(this.previousCullingMode);
            }
        }
    }

    /**
     * Both formats keep their sections on primitive boundaries across a relocation of the arena.
     */
    @Test
    void formatsOfEveryPassShareOneArenaAndStayOnPrimitiveBoundaries() {
        SectionRenderDataStorage compactStorage = this.region.createStorage(COMPACT_PASS, CONFIGURATION);
        SectionRenderDataStorage vanillaLikeStorage = this.region.createStorage(VANILLA_LIKE_PASS, CONFIGURATION);

        RenderRegion.DeviceResources resources = this.region.createResources(this.commandList);
        assertSame(resources, this.region.createResources(this.commandList),
                "a second request returns the resources the region already owns");
        assertSame(resources, this.region.getResources(),
                "the region exposes the one resource set that every pass draws from");

        Map<ModelQuadFacing, VertexRange> oneQuad = Map.of(ModelQuadFacing.UNASSIGNED,
                new VertexRange(0, PRIMITIVE_TYPE.getVerticesPerPrimitive()));
        int compactSection = LocalSectionIndex.pack(0, 0, 0);
        int vanillaLikeSection = LocalSectionIndex.pack(1, 0, 0);

        compactStorage.setMeshes(compactSection, upload(resources.getGeometryArena(), vertexBytes(COMPACT_PASS)),
                null, oneQuad);
        vanillaLikeStorage.setMeshes(vanillaLikeSection,
                upload(resources.getGeometryArena(), vertexBytes(VANILLA_LIKE_PASS)), null, oneQuad);

        upload(resources.getGeometryArena(), LARGE_GROWTH_BYTES);
        this.region.refresh(this.commandList);

        assertBaseVertexOnPrimitiveBoundary(compactStorage, compactSection, COMPACT_PASS);
        assertBaseVertexOnPrimitiveBoundary(vanillaLikeStorage, vanillaLikeSection, VANILLA_LIKE_PASS);
    }

    /**
     * Each vertex array the region is drawn with keeps its own tessellation, and a buffer change drops all of them so
     * the next draw builds them again.
     */
    @Test
    void eachPassKeepsItsOwnTessellationAndARefreshDropsThem() {
        RenderRegion.DeviceResources resources = this.region.createResources(this.commandList);

        GlVertexArray compactArray = new GlVertexArray();
        GlVertexArrayTessellation compactTessellation = new GlVertexArrayTessellation(compactArray,
                vertexArrayBindings(resources, COMPACT_PASS));
        compactTessellation.init(this.commandList);
        GlVertexArray vanillaLikeArray = new GlVertexArray();
        GlVertexArrayTessellation vanillaLikeTessellation = new GlVertexArrayTessellation(vanillaLikeArray,
                vertexArrayBindings(resources, VANILLA_LIKE_PASS));
        vanillaLikeTessellation.init(this.commandList);

        resources.updateTessellation(this.commandList, COMPACT_PASS.tessellationKey(), compactTessellation);
        resources.updateTessellation(this.commandList, VANILLA_LIKE_PASS.tessellationKey(), vanillaLikeTessellation);

        assertSame(compactTessellation, resources.getTessellation(COMPACT_PASS.tessellationKey()),
                "the compact pass keeps the tessellation of its vertex array");
        assertSame(vanillaLikeTessellation, resources.getTessellation(VANILLA_LIKE_PASS.tessellationKey()),
                "the other vertex format gets a tessellation of its own");
        assertSame(compactTessellation, resources.getTessellation(SECOND_COMPACT_PASS.tessellationKey()),
                "a pass that binds the same vertex array reuses its tessellation");

        int compactHandle = compactArray.handle();
        int vanillaLikeHandle = vanillaLikeArray.handle();

        this.region.refresh(this.commandList);

        assertNull(resources.getTessellation(COMPACT_PASS.tessellationKey()),
                "a refresh drops the tessellation of the compact vertex array");
        assertNull(resources.getTessellation(VANILLA_LIKE_PASS.tessellationKey()),
                "a refresh drops the tessellation of the other vertex format");
        assertFalse(GL30C.glIsVertexArray(compactHandle),
                "the vertex array of a dropped tessellation is deleted");
        assertFalse(GL30C.glIsVertexArray(vanillaLikeHandle),
                "the vertex array of a dropped tessellation is deleted whatever its format");

        GlVertexArrayTessellation rebuilt = new GlVertexArrayTessellation(new GlVertexArray(),
                vertexArrayBindings(resources, COMPACT_PASS));
        rebuilt.init(this.commandList);
        resources.updateTessellation(this.commandList, COMPACT_PASS.tessellationKey(), rebuilt);

        assertSame(rebuilt, resources.getTessellation(COMPACT_PASS.tessellationKey()),
                "the pass prepares a new tessellation after the refresh");
    }

    /**
     * The sorted tessellation binds the index arena, so releasing that arena has to drop the tessellations and the
     * batches assembled from it in the same step.
     */
    @Test
    void releasingTheEmptyIndexArenaDropsTessellationsAndCachedBatches() {
        RenderRegion.DeviceResources resources = this.region.createResources(this.commandList);
        SectionRenderDataStorage storage = this.region.createStorage(VANILLA_LIKE_PASS, CONFIGURATION);
        int sectionIndex = LocalSectionIndex.pack(0, 0, 0);
        storage.setMeshes(sectionIndex, upload(resources.getGeometryArena(), vertexBytes(VANILLA_LIKE_PASS)), null,
                Map.of(ModelQuadFacing.UNASSIGNED, new VertexRange(0, PRIMITIVE_TYPE.getVerticesPerPrimitive())));

        GlVertexArrayTessellation tessellation = new GlVertexArrayTessellation(new GlVertexArray(),
                vertexArrayBindings(resources, VANILLA_LIKE_PASS));
        tessellation.init(this.commandList);
        resources.updateTessellation(this.commandList, VANILLA_LIKE_PASS.tessellationKey(), tessellation);
        resources.getOrCreateIndexArena(this.commandList);

        SectionRenderDataStorage.BatchCacheParams params = SectionRenderDataStorage.BatchCacheParams.of(false, 0);
        CachedBatch cached = BatchAssembler.createCachedBatch(this.region, storage, renderList(sectionIndex), CAMERA,
                VANILLA_LIKE_PASS, false, this.commandList, MultiDrawMode.DIRECT,
                (commandList, region, pass) -> tessellation);
        storage.storeCachedMultiDrawBatch(params, cached);

        assertSame(cached, storage.getCachedMultiDrawBatch(params), "the assembled batch is cached under its key");
        assertSame(tessellation, cached.getTessellation(),
                "a batch that holds commands carries the tessellation of its pass");

        this.region.update(this.commandList);

        assertNull(resources.getIndexArena(), "an index arena without allocations is released");
        assertNull(storage.getCachedMultiDrawBatch(params),
                "the released index arena invalidates the batches assembled from it");
        assertNull(resources.getTessellation(VANILLA_LIKE_PASS.tessellationKey()),
                "the tessellations that bind the released arena are dropped with it");
    }

    /**
     * A region whose arena holds nothing releases its resources, which drops the cached batches of its passes. A batch
     * that assembled no commands never asked for a tessellation, so dropping it cannot leave a vertex array behind.
     */
    @Test
    void emptyBatchIsCachedWithoutATessellationAndDroppedWithTheResources() {
        SectionRenderDataStorage storage = this.region.createStorage(VANILLA_LIKE_PASS, CONFIGURATION);
        this.region.createResources(this.commandList);
        SectionRenderDataStorage.BatchCacheParams params = SectionRenderDataStorage.BatchCacheParams.of(false, 0);

        CachedBatch cached = BatchAssembler.createCachedBatch(this.region, storage,
                renderList(LocalSectionIndex.pack(0, 0, 0)), CAMERA, VANILLA_LIKE_PASS, false, this.commandList,
                MultiDrawMode.DIRECT, (commandList, region, pass) -> {
                    throw new AssertionError("a batch without commands must not ask for a tessellation");
                });
        storage.storeCachedMultiDrawBatch(params, cached);

        assertNull(cached.getTessellation(), "a batch without commands carries no tessellation");
        assertSame(cached, storage.getCachedMultiDrawBatch(params), "an empty batch is cached like any other");

        this.region.update(this.commandList);

        assertNull(this.region.getResources(), "a region whose arena holds no allocation releases its resources");
        assertNull(storage.getCachedMultiDrawBatch(params),
                "releasing the resources invalidates the cached batches of the region");
    }

    private static void assertBaseVertexOnPrimitiveBoundary(SectionRenderDataStorage storage, int sectionIndex,
                                                            TerrainRenderPass pass) {
        SectionRenderDataUnsafe.Strategy strategy = storage.getStrategy();
        long row = storage.getDataPointer(sectionIndex);
        int baseVertex = strategy.getVertexOffset(row, ModelQuadFacing.UNASSIGNED.ordinal());
        int verticesPerPrimitive = pass.primitiveType().getVerticesPerPrimitive();

        assertEquals(0, baseVertex % verticesPerPrimitive,
                "a section of the " + pass.name() + " pass starts at base vertex " + baseVertex
                        + ", which is not a boundary of the " + verticesPerPrimitive
                        + "-vertex primitives its shader derives");
    }

    private ChunkRenderList renderList(int sectionIndex) {
        ChunkRenderList renderList = new ChunkRenderList(this.region);
        renderList.add(sectionIndex, BLOCK_GEOMETRY_FLAG);
        return renderList;
    }

    private static int vertexBytes(TerrainRenderPass pass) {
        return pass.primitiveType().getVerticesPerPrimitive() * pass.vertexType().getVertexFormat().getStride();
    }

    private static TessellationBinding[] vertexArrayBindings(RenderRegion.DeviceResources resources,
                                                             TerrainRenderPass pass) {
        Collection<GlVertexAttribute> attributes = pass.vertexType().getVertexFormat().getAttributes();
        var bindings = new GlVertexAttributeBinding[attributes.size()];
        int index = 0;

        for (var attribute : attributes) {
            bindings[index] = new GlVertexAttributeBinding(index, attribute);
            index++;
        }

        return new TessellationBinding[] { TessellationBinding.forVertexBuffer(resources.getVertexBuffer(), bindings) };
    }

    private GlBufferSegment upload(GlBufferArena arena, int bytes) {
        NativeBuffer data = NativeBuffer.copy(ByteBuffer.allocateDirect(bytes));
        try {
            PendingUpload pending = PendingUpload.of(data);
            arena.upload(this.commandList, Stream.of(pending));
            return pending.getResult();
        } finally {
            data.free();
        }
    }

    private static TerrainRenderPass pass(String name, ChunkVertexType vertexType) {
        return TerrainRenderPass.builder()
                .name(name)
                .primitiveType(PRIMITIVE_TYPE)
                .vertexType(vertexType)
                .build();
    }

    private static RenderPassConfiguration<Object> configuration() {
        Material material = new Material(COMPACT_PASS, AlphaCutoffParameter.ZERO, true);
        // The pass set is what the region derives its common vertex stride from, so the fixture declares every pass it
        // creates a storage for.
        Map<Object, Collection<TerrainRenderPass>> stages = Map.of("shared-arena",
                List.of(COMPACT_PASS, VANILLA_LIKE_PASS, SECOND_COMPACT_PASS));

        return new RenderPassConfiguration<>(Map.of(), stages, material, material, material);
    }
}
