package com.dhj.actinium.render.voxelization;

import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderVisualsService;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.TestRenderRegions;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.compat.Lwjgl3GLCapabilitiesShim;
import com.mitchej123.lwjgl.LWJGLServiceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the render-list walk that feeds the SDL GPU voxelization compute.
 *
 * <p>Everything here is built from the public APIs the real renderer uses - a region with a storage
 * created through {@code createStorage}, section metadata written through the storage strategy, and a
 * render list filled with {@code ChunkRenderList.add} - so the assertions describe what the pass
 * would actually draw rather than a shaped mock of it.</p>
 */
class ShadowVoxelizerTest {

    private static final ChunkVertexType VERTEX_TYPE = ChunkMeshFormats.VANILLA_LIKE;
    private static final RenderPassConfiguration<Object> PASS_CONFIGURATION =
        new RenderPassConfiguration<>(Map.of(), Map.of(), null, null, null, null);

    private static class RecordingSink implements ShadowVoxelizer.Sink {
        final List<float[]> regions = new ArrayList<>();
        final List<int[]> ranges = new ArrayList<>();
        boolean refuseRegion;

        @Override
        public boolean region(RenderRegion region, GlVertexFormat format, float x, float y, float z) {
            if (refuseRegion) return false;
            regions.add(new float[]{x, y, z});
            return true;
        }

        @Override
        public void range(int vertexOffset, int vertexCount) {
            ranges.add(new int[]{vertexOffset, vertexCount});
        }
    }

    private final List<SectionRenderDataStorage> storages = new ArrayList<>();

    private static boolean fabricatedCapabilities;

    /**
     * Prepares the native metadata heap these tests write into.
     *
     * <p>Building a storage asks {@code LWJGLServiceProvider} for a service, and the GL-backed one can
     * only report its version after querying GL capabilities - which a test JVM has none of. Installing
     * the SDL backend's fabricated capabilities advertises the modern GL feature set without a context,
     * and no GL call is made afterwards. If another test already forced the provider to initialize
     * without capabilities, the class stays unusable for the rest of the JVM and these tests are
     * skipped, like the other native-memory tests in this source set.</p>
     */
    @BeforeAll
    static void prepareNativeMetadataMemory() {
        try {
            GL.getCapabilities();
        } catch (IllegalStateException e) {
            Lwjgl3GLCapabilitiesShim.installOnCurrentThread(SDLGPURenderBackend.advertisedCapabilities());
            fabricatedCapabilities = true;
        }

        boolean available;
        try {
            available = LWJGLServiceProvider.LWJGL.getPointerSize() > 0;
        } catch (Throwable unavailable) {
            available = false;
        }
        assumeTrue(available, "the native metadata heap is unavailable in this JVM");
    }

    @AfterAll
    static void clearFabricatedGlCapabilities() {
        if (fabricatedCapabilities) {
            Lwjgl3GLCapabilitiesShim.clearOnCurrentThread();
        }
    }

    @AfterEach
    void tearDown() {
        storages.forEach(SectionRenderDataStorage::delete);
        storages.clear();
    }

    private static TerrainRenderPass pass(String name) {
        return pass(name, false);
    }

    private static TerrainRenderPass pass(String name, boolean sorted) {
        return new TerrainRenderPass(name, null, false, false, sorted, false, VERTEX_TYPE,
            QuadPrimitiveType.TRIANGULATED, Map.of());
    }

    /** Creates a region with a storage for the pass, sized to the pass's layout. */
    private SectionRenderDataStorage storageFor(RenderRegion region, TerrainRenderPass renderPass) {
        final SectionRenderDataStorage storage = region.createStorage(renderPass, PASS_CONFIGURATION);
        storages.add(storage);
        return storage;
    }

    private static ChunkRenderList renderList(RenderRegion region, int... localSectionIndices) {
        final ChunkRenderList list = new ChunkRenderList(region);
        for (int sectionIndex : localSectionIndices) {
            list.add(sectionIndex, 1 << RenderVisualsService.HAS_BLOCK_GEOMETRY);
        }
        return list;
    }

    private static ChunkRenderListIterable lists(ChunkRenderList... lists) {
        return new ChunkRenderListIterable() {
            @Override
            public Iterator<ChunkRenderList> iterator(boolean reverse) {
                return List.of(lists).iterator();
            }

            @Override
            public int getNumRegions() {
                return lists.length;
            }

            @Override
            public ChunkRenderList getRegion(int index) {
                return lists[index];
            }
        };
    }

    /** Writes one section's per-facing vertex counts into the storage's mesh rows. */
    private static void writeSection(SectionRenderDataStorage storage, int localSectionIndex, int baseVertex, int... vertsPerFacing) {
        final Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        int vertex = baseVertex;
        for (int f = 0; f < vertsPerFacing.length; f++) {
            if (vertsPerFacing[f] > 0) {
                ranges.put(ModelQuadFacing.VALUES[f], new VertexRange(vertex, vertsPerFacing[f]));
            }
            vertex += vertsPerFacing[f];
        }
        storage.setMeshes(localSectionIndex, new DetachedSegment(baseVertex), null, ranges);
    }

    /**
     * Vertex range that no GL arena owns: the metadata under test is never uploaded, but the storage
     * would free a real segment through its arena when it is deleted.
     */
    private static final class DetachedSegment extends GlBufferSegment {
        DetachedSegment(int offset) {
            super(null, offset, 0);
        }

        @Override
        public void delete() {
        }
    }

    private static RecordingSink walk(ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean faceCulling) {
        final RecordingSink sink = new RecordingSink();
        new ShadowVoxelizer().walkPass(renderLists, renderPass, null, camera, camera, faceCulling, sink);
        return sink;
    }

    @Test
    void emittedRangesCoverMultipleSectionsInARegion() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(0, 0, 0, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 4, 0, 8, 4, 0, 0, 12);
        writeSection(storage, 5, 100, 0, 16, 0, 0, 4, 0, 0);

        final RecordingSink sink = walk(lists(renderList(region, 0, 5)), renderPass, new CameraTransform(0, 0, 0), false);

        assertEquals(1, sink.regions.size(), "one region announced once");
        assertEquals(5, sink.ranges.size(), "section 0's facings 2-3 merge, section 5's facings stay separate");
        assertArrayEquals(new int[]{0, 4}, sink.ranges.get(0), "section 0 facing 0");
        assertArrayEquals(new int[]{4, 12}, sink.ranges.get(1), "section 0 facings 2-3");
        assertArrayEquals(new int[]{16, 12}, sink.ranges.get(2), "section 0 facing 6");
        assertArrayEquals(new int[]{100, 16}, sink.ranges.get(3), "section 5 facing 1");
        assertArrayEquals(new int[]{116, 4}, sink.ranges.get(4), "section 5 facing 4");
    }

    @Test
    void regionOffsetIsCameraRelative() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(1, 0, 3, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 4, 0, 0, 0, 0, 0, 0);
        final CameraTransform camera = new CameraTransform(70.25, 12.5, -3.75);

        final RecordingSink sink = walk(lists(renderList(region, 0)), renderPass, camera, false);

        assertEquals(1, sink.regions.size());
        final float[] offset = sink.regions.get(0);
        assertEquals(region.getOriginX() - camera.intX - camera.fracX, offset[0], 0f, "x");
        assertEquals(region.getOriginY() - camera.intY - camera.fracY, offset[1], 0f, "y");
        assertEquals(region.getOriginZ() - camera.intZ - camera.fracZ, offset[2], 0f, "z");
    }

    @Test
    void regionOffsetUsesTheRegionOriginNotABlockGrid() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(1, 0, 0, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 4, 0, 0, 0, 0, 0, 0);

        final RecordingSink sink = walk(lists(renderList(region, 0)), renderPass, new CameraTransform(0, 0, 0), false);

        assertEquals(region.getOriginX(), sink.regions.get(0)[0], 0f, "region 1 starts at its own origin");
    }

    @Test
    void contiguousFacingsCollapseToOneRange() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(0, 0, 0, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 4, 4, 4, 4, 4, 4, 4);

        final RecordingSink sink = walk(lists(renderList(region, 0)), renderPass, new CameraTransform(0, 0, 0), false);

        assertEquals(1, sink.ranges.size(), "seven adjacent facings are one vertex span, not seven dispatches");
        assertEquals(0, sink.ranges.get(0)[0]);
        assertEquals(28, sink.ranges.get(0)[1]);
    }

    @Test
    void gapsInTheFacingMaskSplitRuns() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(0, 0, 0, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 4, 0, 8, 0, 0, 0, 4);

        final RecordingSink sink = walk(lists(renderList(region, 0)), renderPass, new CameraTransform(0, 0, 0), false);

        assertEquals(3, sink.ranges.size());
        assertArrayEquals(new int[]{0, 4}, sink.ranges.get(0), "facing 0");
        assertArrayEquals(new int[]{4, 8}, sink.ranges.get(1), "facing 2");
        assertArrayEquals(new int[]{12, 4}, sink.ranges.get(2), "facing 6");
    }

    @Test
    void sectionsWithoutGeometryNeverAnnounceTheRegion() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(0, 0, 0, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        final RecordingSink sink = walk(lists(renderList(region, 0)), renderPass, new CameraTransform(0, 0, 0), false);

        assertTrue(sink.regions.isEmpty(), "an empty region must not open an encoder");
        assertTrue(sink.ranges.isEmpty());
    }

    @Test
    void missingStorageForThePassIsSkipped() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(0, 0, 0, 0);

        final RecordingSink sink = walk(lists(renderList(region, 0)), renderPass, new CameraTransform(0, 0, 0), false);

        assertTrue(sink.ranges.isEmpty());
    }

    @Test
    void unbindableRegionIsSkippedButThePassContinues() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion regionA = TestRenderRegions.create(0, 0, 0, 0);
        final RenderRegion regionB = TestRenderRegions.create(1, 0, 0, 1);
        writeSection(storageFor(regionA, renderPass), 0, 0, 4, 0, 0, 0, 0, 0, 0);
        writeSection(storageFor(regionB, renderPass), 0, 0, 4, 0, 0, 0, 0, 0, 0);

        final RecordingSink sink = new RecordingSink();
        sink.refuseRegion = true;
        new ShadowVoxelizer().walkPass(lists(renderList(regionA, 0), renderList(regionB, 0)), renderPass, null,
            new CameraTransform(0, 0, 0), new CameraTransform(0, 0, 0), false, sink);

        assertTrue(sink.ranges.isEmpty(), "a region that cannot be bound emits nothing");
    }

    @Test
    void faceCullingNarrowsTheEmittedRanges() {
        final TerrainRenderPass renderPass = pass("solid");
        final RenderRegion region = TestRenderRegions.create(0, 0, 0, 0);
        final SectionRenderDataStorage storage = storageFor(region, renderPass);
        writeSection(storage, 0, 0, 4, 4, 4, 4, 4, 4, 4);
        final ChunkRenderListIterable renderLists = lists(renderList(region, 0));
        final CameraTransform camera = new CameraTransform(500, 200, 500);

        final RecordingSink sink = walk(renderLists, renderPass, camera, true);

        assertFalse(sink.ranges.isEmpty(), "face culling removed everything; the fixture is not exercising the path");
        assertTrue(sink.ranges.size() < 7 || sink.ranges.get(0)[1] < 28, "face culling did not narrow anything");
    }

    @Test
    void sortedPassWalksTheFullLayoutAndYieldsTheSameSpansAsCompact() {
        final int[] facings = {4, 0, 8, 0, 0, 0, 4};

        final TerrainRenderPass sortedPass = pass("translucent", true);
        final RenderRegion sortedRegion = TestRenderRegions.create(0, 0, 0, 0);
        final SectionRenderDataStorage sortedStorage = storageFor(sortedRegion, sortedPass);
        writeSection(sortedStorage, 0, 0, facings);
        writeSection(sortedStorage, 5, 100, facings);

        final TerrainRenderPass solidPass = pass("solid");
        final RenderRegion solidRegion = TestRenderRegions.create(0, 0, 0, 1);
        final SectionRenderDataStorage solidStorage = storageFor(solidRegion, solidPass);
        writeSection(solidStorage, 0, 0, facings);
        writeSection(solidStorage, 5, 100, facings);

        final RecordingSink sorted = walk(lists(renderList(sortedRegion, 0, 5)), sortedPass, new CameraTransform(0, 0, 0), false);
        final RecordingSink compact = walk(lists(renderList(solidRegion, 0, 5)), solidPass, new CameraTransform(0, 0, 0), false);

        assertEquals(1, sorted.regions.size(), "the sorted region is announced once");
        assertFalse(sorted.ranges.isEmpty(), "the FULL layout walk produced nothing");
        assertEquals(6, compact.ranges.size(), "two sections of three runs each");
        assertEquals(compact.ranges.size(), sorted.ranges.size(), "FULL and COMPACT disagree on run count");
        for (int i = 0; i < compact.ranges.size(); i++) {
            assertArrayEquals(compact.ranges.get(i), sorted.ranges.get(i), "range " + i);
        }
    }
}
