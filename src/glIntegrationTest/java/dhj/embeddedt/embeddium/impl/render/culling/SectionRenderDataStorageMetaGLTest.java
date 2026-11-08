package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferArena;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.arena.PendingUpload;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegionManager;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the storage to section-mirror integration through real render regions, real GL buffer segments and the real
 * region arena upload path. Every assertion reads what the compute cull shader would read: the slot the storage
 * caches for a section, and the mirrored row at that slot.
 *
 * <p>The mirror is only maintained while the compute path is both selected and available, so the fixture brings up the
 * real GL state machine the availability probe reads and drives the multidraw mode the compute batches need.
 */
class SectionRenderDataStorageMetaGLTest extends GLStateManagerIntegrationFixture {
    private static final ChunkPrimitiveType PRIMITIVE_TYPE = QuadPrimitiveType.TRIANGULATED;
    private static final int REGION_CHUNK_X = 8;
    private static final int REGION_CHUNK_Y = 4;
    private static final int REGION_CHUNK_Z = 0;

    private static final TerrainRenderPass UNSORTED_PASS = pass("meta-storage-solid", false);
    private static final TerrainRenderPass SORTED_PASS = pass("meta-storage-translucent", true);
    private static final TerrainRenderPass SECOND_SORTED_PASS = pass("meta-storage-translucent-2", true);
    private static final RenderPassConfiguration<Object> CONFIGURATION = configuration();

    private CommandList commandList;
    private RenderRegionManager regionManager;
    private RenderRegion region;
    private RenderRegion.DeviceResources resources;
    private SectionRenderDataStorage unsortedStorage;
    private SectionRenderDataStorage sortedStorage;
    private GpuCullingMode previousMode;
    private MultiDrawMode previousMultiDrawMode;

    @BeforeAll
    static void requireComputeContext() {
        assumeTrue(GL.getCapabilities().OpenGL43, "the compute cull path needs a GL 4.3 context");
        assumeTrue(MultiDrawModeResolver.supportsMultiDrawIndirect(), "context reports no multi-draw-indirect support");
    }

    @BeforeEach
    void setUp() {
        // Publishing to the section mirror requires the compute path, and the compute path needs the indirect multidraw
        // mode, so both switches are saved and restored.
        this.previousMode = GpuCulling.mode();
        GpuCulling.setMode(GpuCullingMode.COMPUTE);
        this.previousMultiDrawMode = EmbeddiumRuntimeOptions.chunkMultiDrawMode();
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> MultiDrawMode.INDIRECT);
        RenderDevice.INSTANCE.makeActive();
        this.commandList = RenderDevice.INSTANCE.createCommandList();
        this.regionManager = new RenderRegionManager(this.commandList, CONFIGURATION);
        this.region = this.regionManager.createForChunk(REGION_CHUNK_X, REGION_CHUNK_Y, REGION_CHUNK_Z);
        this.resources = this.region.createResources(UNSORTED_PASS.vertexType().getVertexFormat(), this.commandList);
        this.unsortedStorage = this.region.createStorage(UNSORTED_PASS, CONFIGURATION);
        this.sortedStorage = this.region.createStorage(SORTED_PASS, CONFIGURATION);
    }

    @AfterEach
    void tearDown() {
        try {
            this.regionManager.delete(this.commandList);
        } finally {
            try {
                GpuCulling.sectionMeta()
                        .reset();
                this.commandList.close();
                RenderDevice.INSTANCE.makeInactive();
            } finally {
                EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> this.previousMultiDrawMode);
                GpuCulling.setMode(this.previousMode);
            }
        }
    }

    @Test
    void regionTagsItsStoragesWithTheOwningRegionAndPassIndex() {
        assertSame(this.region, this.unsortedStorage.getRegion(),
                "the region that creates a storage owns it");
        assertEquals(RenderPassIndex.indexOf(UNSORTED_PASS), this.unsortedStorage.getPassIndex(),
                "the storage is keyed by its pass index");
        assertNotEquals(this.unsortedStorage.getPassIndex(), this.sortedStorage.getPassIndex(),
                "two passes of one region take different indices");
        assertNull(this.unsortedStorage.getSlotCache(),
                "a storage that has not published a section holds no slots");
        assertSame(this.unsortedStorage, this.region.createStorage(UNSORTED_PASS, CONFIGURATION),
                "asking for the pass again returns the tagged storage");
    }

    @Test
    void setMeshesPublishesTheSectionSlot() {
        int localSectionIndex = LocalSectionIndex.pack(1, 2, 3);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));
        ranges.put(ModelQuadFacing.NEG_Z, new VertexRange(8, 16));

        GlBufferSegment geometry = upload(this.resources.getGeometryArena(), 24 * 16);
        this.unsortedStorage.setMeshes(localSectionIndex, geometry, null, ranges);

        int[] slots = this.unsortedStorage.getSlotCache();
        assertNotNull(slots, "publishing a section allocates the slot cache");
        assertTrue(slots[localSectionIndex] >= 0, "the published section holds a slot");

        for (int otherIndex = 0; otherIndex < slots.length; otherIndex++) {
            if (otherIndex != localSectionIndex) {
                assertEquals(-1, slots[otherIndex], "an unpublished section " + otherIndex + " holds no slot");
            }
        }

        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        assertNotNull(mirror, "the mirror exists once a section is published");
        int base = slots[localSectionIndex] * SectionMetaBuffer.BYTES_PER_SECTION;
        assertEquals(this.region.getOriginX() + (LocalSectionIndex.unpackX(localSectionIndex) << 4),
                mirror.getInt(base), "the slot carries the section's world x");
        assertEquals(this.region.getOriginY() + (LocalSectionIndex.unpackY(localSectionIndex) << 4),
                mirror.getInt(base + 4), "the slot carries the section's world y");
        assertEquals(this.region.getOriginZ() + (LocalSectionIndex.unpackZ(localSectionIndex) << 4),
                mirror.getInt(base + 8), "the slot carries the section's world z");

        int expectedSliceMask = (1 << ModelQuadFacing.POS_X.ordinal()) | (1 << ModelQuadFacing.NEG_Z.ordinal());
        assertEquals(this.unsortedStorage.getSliceMask(localSectionIndex),
                mirror.getInt(base + SectionMetaBuffer.OFFSET_SLICE_MASK),
                "the slot carries the row's slice mask");
        assertEquals(expectedSliceMask, mirror.getInt(base + SectionMetaBuffer.OFFSET_SLICE_MASK),
                "the slice mask names the facings the section built");

        long rowPointer = this.unsortedStorage.getDataPointer(localSectionIndex);
        SectionRenderDataUnsafe.Strategy strategy = this.unsortedStorage.getStrategy();
        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            assertEquals(strategy.getVertexOffset(rowPointer, facing),
                    mirror.getInt(base + SectionMetaBuffer.OFFSET_POSTS + facing * 4),
                    "post[" + facing + "] matches the row's facing start");
        }
        assertEquals(strategy.getRunVertexEnd(rowPointer, ModelQuadFacing.COUNT - 1, PRIMITIVE_TYPE),
                mirror.getInt(base + SectionMetaBuffer.OFFSET_POSTS + ModelQuadFacing.COUNT * 4),
                "the last post ends the row's vertex range");
    }

    @Test
    void replaceIndexBufferRepublishesTheIndexBase() {
        int localSectionIndex = LocalSectionIndex.pack(0, 0, 0);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.UNASSIGNED, new VertexRange(0, 8));

        GlBufferArena indexArena = this.resources.getOrCreateIndexArena(this.commandList);
        GlBufferSegment firstIndex = upload(indexArena, 6 * 4);
        GlBufferSegment secondIndex = upload(indexArena, 6 * 4);
        this.sortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), firstIndex,
                ranges);

        int slot = this.sortedStorage.getSlotCache()[localSectionIndex];
        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        assertEquals(firstIndex.getOffset(),
                mirror.getInt(slot * SectionMetaBuffer.BYTES_PER_SECTION + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "the slot carries the index base of the first index allocation");

        this.sortedStorage.replaceIndexBuffer(localSectionIndex, secondIndex);

        assertEquals(secondIndex.getOffset(),
                mirror.getInt(slot * SectionMetaBuffer.BYTES_PER_SECTION + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "replacing the index buffer republishes the slot's index base");
    }

    @Test
    void twoSortedPassesPublishIndependentRows() {
        int localSectionIndex = LocalSectionIndex.pack(1, 1, 0);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.UNASSIGNED, new VertexRange(0, 8));

        SectionRenderDataStorage secondSortedStorage = this.region.createStorage(SECOND_SORTED_PASS, CONFIGURATION);
        GlBufferArena indexArena = this.resources.getOrCreateIndexArena(this.commandList);
        GlBufferSegment firstIndex = upload(indexArena, 6 * 4);
        GlBufferSegment secondIndex = upload(indexArena, 6 * 4);

        this.sortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), firstIndex,
                ranges);
        secondSortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), secondIndex,
                ranges);

        assertEquals(SectionRenderDataUnsafe.Strategy.FULL, this.sortedStorage.getStrategy(),
                "a sorted pass keeps the full per-facing metadata");
        assertEquals(SectionRenderDataUnsafe.Strategy.FULL, secondSortedStorage.getStrategy(),
                "the second sorted pass keeps it too");

        int firstSlot = this.sortedStorage.getSlotCache()[localSectionIndex];
        int secondSlot = secondSortedStorage.getSlotCache()[localSectionIndex];
        assertNotEquals(firstSlot, secondSlot, "each sorted pass owns the slot of the section");

        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        int firstBase = firstSlot * SectionMetaBuffer.BYTES_PER_SECTION;
        int secondBase = secondSlot * SectionMetaBuffer.BYTES_PER_SECTION;
        int sectionOriginX = this.region.getOriginX() + (LocalSectionIndex.unpackX(localSectionIndex) << 4);
        int sectionOriginY = this.region.getOriginY() + (LocalSectionIndex.unpackY(localSectionIndex) << 4);
        int sectionOriginZ = this.region.getOriginZ() + (LocalSectionIndex.unpackZ(localSectionIndex) << 4);

        for (int base : new int[] { firstBase, secondBase }) {
            assertEquals(sectionOriginX, mirror.getInt(base), "both passes address the same section x");
            assertEquals(sectionOriginY, mirror.getInt(base + 4), "both passes address the same section y");
            assertEquals(sectionOriginZ, mirror.getInt(base + 8), "both passes address the same section z");
        }

        assertEquals(firstIndex.getOffset(), mirror.getInt(firstBase + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "the first sorted pass keeps its own index base");
        assertEquals(secondIndex.getOffset(), mirror.getInt(secondBase + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "republishing one sorted pass leaves the other's index base alone");

        SectionRenderDataUnsafe.Strategy strategy = this.sortedStorage.getStrategy();
        long firstRow = this.sortedStorage.getDataPointer(localSectionIndex);
        long secondRow = secondSortedStorage.getDataPointer(localSectionIndex);
        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            assertEquals(strategy.getVertexOffset(firstRow, facing),
                    mirror.getInt(firstBase + SectionMetaBuffer.OFFSET_POSTS + facing * 4),
                    "the first sorted pass publishes post[" + facing + "] of its own row");
            assertEquals(strategy.getVertexOffset(secondRow, facing),
                    mirror.getInt(secondBase + SectionMetaBuffer.OFFSET_POSTS + facing * 4),
                    "the second sorted pass publishes post[" + facing + "] of its own row");
        }

        this.sortedStorage.removeMeshes(localSectionIndex);

        assertEquals(-1, this.sortedStorage.getSlotCache()[localSectionIndex],
                "removing one sorted pass's meshes releases its slot");
        assertEquals(secondSlot, secondSortedStorage.getSlotCache()[localSectionIndex],
                "the sibling sorted pass keeps its slot");
        assertEquals(0, mirror.getInt(firstBase + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "the released slot is cleared");
        assertEquals(secondIndex.getOffset(), mirror.getInt(secondBase + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "the sibling sorted pass keeps its index base");
    }

    @Test
    void arenaGrowthRepublishesRebasedRows() {
        int localSectionIndex = LocalSectionIndex.pack(2, 1, 0);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_Y, new VertexRange(0, 8));

        GlBufferSegment geometry = upload(this.resources.getGeometryArena(), 8 * 16);
        this.sortedStorage.setMeshes(localSectionIndex, geometry, null, ranges);

        int slot = this.sortedStorage.getSlotCache()[localSectionIndex];
        int slotBase = slot * SectionMetaBuffer.BYTES_PER_SECTION;
        int publishedOffset = geometry.getOffset();

        // Growing the arena moves every live segment to the tail of the new buffer.
        upload(this.resources.getGeometryArena(), 600 * 1024);
        assertNotEquals(publishedOffset, geometry.getOffset(), "arena growth relocates the live segment");
        assertEquals(publishedOffset,
                GpuCulling.sectionMeta()
                        .getMirrorForReadOnly()
                        .getInt(slotBase + SectionMetaBuffer.OFFSET_POSTS),
                "the slot still holds the offset of the pre-growth segment");

        this.sortedStorage.onBufferResized();

        SectionRenderDataUnsafe.Strategy strategy = this.sortedStorage.getStrategy();
        assertEquals(geometry.getOffset(),
                strategy.getVertexOffset(this.sortedStorage.getDataPointer(localSectionIndex), 0),
                "the resize rebases the row onto the relocated segment");
        assertEquals(geometry.getOffset(),
                GpuCulling.sectionMeta()
                        .getMirrorForReadOnly()
                        .getInt(slotBase + SectionMetaBuffer.OFFSET_POSTS),
                "the slot is republished with the rebased vertex offset");
    }

    @Test
    void removeMeshesReleasesTheSlotForTheNextSection() {
        int localSectionIndex = LocalSectionIndex.pack(0, 0, 0);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));

        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);
        int slot = this.unsortedStorage.getSlotCache()[localSectionIndex];
        int highWaterMark = GpuCulling.sectionMeta()
                .getHighWaterMark();

        this.unsortedStorage.removeMeshes(localSectionIndex);

        assertEquals(-1, this.unsortedStorage.getSlotCache()[localSectionIndex],
                "removing the meshes releases the section's slot");
        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        for (int offset = 0; offset < SectionMetaBuffer.BYTES_PER_SECTION; offset += Integer.BYTES) {
            assertEquals(0, mirror.getInt(slot * SectionMetaBuffer.BYTES_PER_SECTION + offset),
                    "the released slot is cleared at byte " + offset);
        }

        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);

        assertEquals(slot, this.unsortedStorage.getSlotCache()[localSectionIndex],
                "the rebuilt section claims the released slot");
        assertEquals(highWaterMark, GpuCulling.sectionMeta()
                .getHighWaterMark(), "slot reuse does not grow the mirror");
    }

    @Test
    void sectionWithoutGeometryHoldsNoSlot() {
        int localSectionIndex = LocalSectionIndex.pack(0, 0, 0);

        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                Map.of());

        int[] slots = this.unsortedStorage.getSlotCache();
        assertTrue(slots == null || slots[localSectionIndex] < 0,
                "a section whose slice mask is empty takes no mirror slot");
    }

    @Test
    void deleteReleasesEverySlot() {
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));

        // Attached by hand so the region keeps no reference to it: deleting the storage is the only cleanup it gets.
        SectionRenderDataStorage detached = new SectionRenderDataStorage(PRIMITIVE_TYPE, false);
        detached.attachRegion(this.region, RenderPassIndex.indexOf(UNSORTED_PASS));

        int firstSection = LocalSectionIndex.pack(0, 0, 0);
        int secondSection = LocalSectionIndex.pack(1, 0, 0);
        detached.setMeshes(firstSection, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);
        detached.setMeshes(secondSection, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);
        int firstSlot = detached.getSlotCache()[firstSection];
        int secondSlot = detached.getSlotCache()[secondSection];

        detached.delete();

        assertEquals(-1, detached.getSlotCache()[firstSection],
                "deleting the storage releases the first slot");
        assertEquals(-1, detached.getSlotCache()[secondSection],
                "deleting the storage releases the second slot");
        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        for (int slot : new int[] { firstSlot, secondSlot }) {
            for (int offset = 0; offset < SectionMetaBuffer.BYTES_PER_SECTION; offset += Integer.BYTES) {
                assertEquals(0, mirror.getInt(slot * SectionMetaBuffer.BYTES_PER_SECTION + offset),
                        "slot " + slot + " is cleared at byte " + offset);
            }
        }
    }

    @Test
    void siblingRegionPublishesItsOwnSlots() {
        RenderRegion neighbour = this.regionManager.createForChunk(REGION_CHUNK_X + RenderRegion.REGION_WIDTH,
                REGION_CHUNK_Y, REGION_CHUNK_Z);
        SectionRenderDataStorage neighbourStorage = neighbour.createStorage(UNSORTED_PASS, CONFIGURATION);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));

        int localSectionIndex = LocalSectionIndex.pack(0, 0, 0);
        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);
        GlBufferSegment neighbourGeometry = upload(
                neighbour.createResources(UNSORTED_PASS.vertexType().getVertexFormat(), this.commandList)
                        .getGeometryArena(), 8 * 16);
        neighbourStorage.setMeshes(localSectionIndex, neighbourGeometry, null, ranges);

        int ownSlot = this.unsortedStorage.getSlotCache()[localSectionIndex];
        int neighbourSlot = neighbourStorage.getSlotCache()[localSectionIndex];
        assertNotEquals(ownSlot, neighbourSlot, "the same section index in another region gets its own slot");

        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        assertEquals(neighbour.getOriginX(),
                mirror.getInt(neighbourSlot * SectionMetaBuffer.BYTES_PER_SECTION),
                "the neighbour slot carries the neighbour region's origin");
        assertEquals(RenderPassIndex.indexOf(UNSORTED_PASS), neighbourStorage.getPassIndex(),
                "the neighbour storage is keyed by the same pass index");
    }

    @Test
    void cpuOnlyModeKeepsTheSectionMirrorEmpty() {
        GpuCulling.setMode(GpuCullingMode.CPU_ONLY);
        int localSectionIndex = LocalSectionIndex.pack(0, 0, 0);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));

        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);

        assertNull(this.unsortedStorage.getSlotCache(), "a CPU-only storage allocates no slot cache");
        assertNull(GpuCulling.sectionMeta()
                .getMirrorForReadOnly(), "a CPU-only storage writes no mirror row");
        assertEquals(0, GpuCulling.sectionMeta()
                .getHighWaterMark(), "a CPU-only storage takes no mirror slot");
    }

    @Test
    void switchingToComputePublishesTheNextSection() {
        int localSectionIndex = LocalSectionIndex.pack(1, 0, 2);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.NEG_Y, new VertexRange(0, 8));

        // The publish gate is decided when a region attaches the storage, so a renderer reload is a fresh storage in a
        // fresh region rather than a mode flip on a storage that was attached earlier.
        GpuCulling.setMode(GpuCullingMode.CPU_ONLY);
        RenderRegion cpuRegion = this.regionManager.createForChunk(REGION_CHUNK_X + RenderRegion.REGION_WIDTH * 2,
                REGION_CHUNK_Y, REGION_CHUNK_Z);
        SectionRenderDataStorage cpuStorage = cpuRegion.createStorage(UNSORTED_PASS, CONFIGURATION);
        cpuStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);
        assertNull(cpuStorage.getSlotCache(),
                "a storage attached while the compute path is off publishes nothing");

        GpuCulling.setMode(GpuCullingMode.COMPUTE);
        assertTrue(GpuCulling.isAvailable(), "the fixture context can run the compute path");

        cpuStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);
        assertNull(cpuStorage.getSlotCache(),
                "the gate was decided at attach time, so the old storage stays out of the mirror until it is reloaded");

        RenderRegion computeRegion = this.regionManager.createForChunk(REGION_CHUNK_X + RenderRegion.REGION_WIDTH * 4,
                REGION_CHUNK_Y, REGION_CHUNK_Z);
        SectionRenderDataStorage computeStorage = computeRegion.createStorage(UNSORTED_PASS, CONFIGURATION);
        computeStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);

        int[] slots = computeStorage.getSlotCache();
        assertNotNull(slots, "the storage attached after the switch allocates the slot cache");
        assertTrue(slots[localSectionIndex] >= 0, "the storage attached after the switch publishes the section");
        assertEquals(computeRegion.getOriginX() + (LocalSectionIndex.unpackX(localSectionIndex) << 4),
                GpuCulling.sectionMeta()
                        .getMirrorForReadOnly()
                        .getInt(slots[localSectionIndex] * SectionMetaBuffer.BYTES_PER_SECTION),
                "the slot published after the switch carries the section's world x");
    }

    @Test
    void switchingToCpuOnlyReleasesTheMirrorAndTheComputeProgram() {
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));
        int localSectionIndex = LocalSectionIndex.pack(2, 1, 3);

        GpuDrivenChunkCuller sharedCuller = GpuCulling.culler();
        assertTrue(sharedCuller.ensureReady(), "the fixture context links the cull program");

        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);
        assertNotNull(GpuCulling.sectionMeta()
                .getMirrorForReadOnly(), "publishing a section allocates the mirror");
        assertTrue(GpuCulling.sectionMeta()
                .getHighWaterMark() > 0, "publishing a section takes a row");

        GpuCulling.setMode(GpuCullingMode.CPU_ONLY);

        assertNull(GpuCulling.sectionMeta()
                .getMirrorForReadOnly(), "the CPU-only mode releases the mirror");
        assertEquals(0, GpuCulling.sectionMeta()
                .getHighWaterMark(), "the CPU-only mode releases every row");
        assertFalse(sharedCuller.ensureReady(), "the released compute program is not reused");

        GpuCulling.setMode(GpuCullingMode.COMPUTE);

        GpuDrivenChunkCuller reloaded = GpuCulling.culler();
        assertNotSame(sharedCuller, reloaded, "the compute mode takes a fresh compute instance");
        assertTrue(reloaded.ensureReady(), "the fresh instance links the cull program again");

        this.unsortedStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);

        int[] slots = this.unsortedStorage.getSlotCache();
        assertTrue(slots[localSectionIndex] >= 0, "the storage republishes into the released mirror");
        assertEquals(slots[localSectionIndex] + 1, GpuCulling.sectionMeta()
                .getHighWaterMark(), "the released mirror holds no row of the previous session");

        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();
        int base = slots[localSectionIndex] * SectionMetaBuffer.BYTES_PER_SECTION;
        assertEquals(this.region.getOriginX() + (LocalSectionIndex.unpackX(localSectionIndex) << 4),
                mirror.getInt(base), "the republished slot carries the section's world x");
        assertEquals(this.region.getOriginY() + (LocalSectionIndex.unpackY(localSectionIndex) << 4),
                mirror.getInt(base + 4), "the republished slot carries the section's world y");
        assertEquals(this.region.getOriginZ() + (LocalSectionIndex.unpackZ(localSectionIndex) << 4),
                mirror.getInt(base + 8), "the republished slot carries the section's world z");
    }

    @Test
    void computeModeWithoutIndirectMultiDrawPublishesNoSlots() {
        int localSectionIndex = LocalSectionIndex.pack(2, 0, 1);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_Z, new VertexRange(0, 8));

        // A direct multidraw context cannot draw the compute batches, which is what the availability probe reports.
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> MultiDrawMode.DIRECT);
        assertTrue(GpuCulling.mode().computeEnabled(), "the compute mode stays selected");
        assertFalse(GpuCulling.isAvailable(), "a direct multidraw context cannot run the compute path");

        RenderRegion unavailableRegion = this.regionManager.createForChunk(
                REGION_CHUNK_X + RenderRegion.REGION_WIDTH * 2, REGION_CHUNK_Y, REGION_CHUNK_Z);
        SectionRenderDataStorage unavailableStorage = unavailableRegion.createStorage(UNSORTED_PASS, CONFIGURATION);
        unavailableStorage.setMeshes(localSectionIndex, upload(this.resources.getGeometryArena(), 8 * 16), null,
                ranges);

        assertNull(unavailableStorage.getSlotCache(), "an unavailable compute path allocates no slot cache");
        assertNull(GpuCulling.sectionMeta()
                .getMirrorForReadOnly(), "an unavailable compute path writes no mirror row");
        assertEquals(0, GpuCulling.sectionMeta()
                .getHighWaterMark(), "an unavailable compute path takes no mirror slot");
    }

    @Test
    void contextThatCannotRunComputeReleasesTheSlotsItAlreadyPublished() {
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));

        SectionRenderDataStorage detached = new SectionRenderDataStorage(PRIMITIVE_TYPE, false);
        detached.attachRegion(this.region, RenderPassIndex.indexOf(UNSORTED_PASS));
        int removedSection = LocalSectionIndex.pack(0, 1, 0);
        int deletedSection = LocalSectionIndex.pack(1, 1, 0);
        detached.setMeshes(removedSection, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);
        detached.setMeshes(deletedSection, upload(this.resources.getGeometryArena(), 8 * 16), null, ranges);
        int removedSlot = detached.getSlotCache()[removedSection];
        int deletedSlot = detached.getSlotCache()[deletedSection];
        assertNotEquals(removedSlot, deletedSlot, "both sections hold their own slot");

        // The mode stays selected, so the mirror and the rows already written stay alive while the context stops
        // offering the compute path.
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> MultiDrawMode.DIRECT);
        assertFalse(GpuCulling.isAvailable(), "a direct multidraw context cannot run the compute path");
        ByteBuffer mirror = GpuCulling.sectionMeta()
                .getMirrorForReadOnly();

        detached.removeMeshes(removedSection);

        assertEquals(-1, detached.getSlotCache()[removedSection],
                "removal releases the slot of a storage whose context cannot run compute");
        assertSlotCleared(mirror, removedSlot);

        detached.delete();

        assertEquals(-1, detached.getSlotCache()[deletedSection],
                "deletion releases the slot the storage still held");
        assertSlotCleared(mirror, deletedSlot);
    }

    private static void assertSlotCleared(ByteBuffer mirror, int slot) {
        for (int offset = 0; offset < SectionMetaBuffer.BYTES_PER_SECTION; offset += Integer.BYTES) {
            assertEquals(0, mirror.getInt(slot * SectionMetaBuffer.BYTES_PER_SECTION + offset),
                    "slot " + slot + " is cleared at byte " + offset);
        }
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

    private static TerrainRenderPass pass(String name, boolean sorted) {
        return TerrainRenderPass.builder()
                .name(name)
                .primitiveType(PRIMITIVE_TYPE)
                .vertexType(new CompactChunkVertex())
                .useTranslucencySorting(sorted)
                .build();
    }

    private static RenderPassConfiguration<Object> configuration() {
        Material material = new Material(UNSORTED_PASS, AlphaCutoffParameter.ZERO, true);

        return new RenderPassConfiguration<>(Map.of(), Map.of(), material, material, material);
    }
}
