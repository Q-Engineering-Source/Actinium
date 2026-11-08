package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataUnsafe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the section mirror through rows written by the production {@link SectionRenderDataUnsafe.Strategy} layout.
 * The mirror is what the compute cull shader reads, so the assertions check the slot's GPU-visible content: section
 * origin, slice mask, the fence posts closing every facing's vertex range, and the section's index base.
 */
class SectionMetaBufferTest extends GLStateManagerIntegrationFixture {
    private static final ChunkPrimitiveType PRIMITIVE_TYPE = QuadPrimitiveType.TRIANGULATED;
    private static final SectionRenderDataUnsafe.Strategy STRATEGY = SectionRenderDataUnsafe.Strategy.FULL;

    private SectionMetaBuffer meta;
    private long heap;

    @BeforeEach
    void setUp() {
        this.meta = new SectionMetaBuffer();
        this.heap = STRATEGY.allocateHeap();
    }

    @AfterEach
    void tearDown() {
        STRATEGY.freeHeap(this.heap);
    }

    @Test
    void slotContentFollowsTheProductionMeshRow() {
        int localSectionIndex = LocalSectionIndex.pack(2, 1, 3);
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(ModelQuadFacing.POS_X, new VertexRange(0, 8));
        ranges.put(ModelQuadFacing.NEG_Z, new VertexRange(8, 16));

        STRATEGY.writeMeshesAndSliceMask(this.heap, localSectionIndex, 4096, 1024, ranges, PRIMITIVE_TYPE);
        long rowPointer = STRATEGY.heapPointer(this.heap, localSectionIndex);
        int sliceMask = STRATEGY.getSliceMask(this.heap, localSectionIndex);

        int regionOriginX = 512;
        int regionOriginY = 64;
        int regionOriginZ = -256;
        int slot = this.meta.update(0, regionOriginX, regionOriginY, regionOriginZ, localSectionIndex,
                rowPointer, sliceMask, STRATEGY, PRIMITIVE_TYPE);

        assertEquals(0, slot, "the first pushed section takes the first slot");
        ByteBuffer mirror = this.meta.getMirrorForReadOnly();
        assertNotNull(mirror, "an update publishes a mirror");

        int base = slot * SectionMetaBuffer.BYTES_PER_SECTION;
        assertEquals(regionOriginX + (LocalSectionIndex.unpackX(localSectionIndex) << 4), mirror.getInt(base),
                "slot origin.x is the section's world x");
        assertEquals(regionOriginY + (LocalSectionIndex.unpackY(localSectionIndex) << 4), mirror.getInt(base + 4),
                "slot origin.y is the section's world y");
        assertEquals(regionOriginZ + (LocalSectionIndex.unpackZ(localSectionIndex) << 4), mirror.getInt(base + 8),
                "slot origin.z is the section's world z");
        assertEquals(sliceMask, mirror.getInt(base + SectionMetaBuffer.OFFSET_SLICE_MASK),
                "slot origin.w carries the section's slice mask");

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            assertEquals(STRATEGY.getVertexOffset(rowPointer, facing),
                    mirror.getInt(base + SectionMetaBuffer.OFFSET_POSTS + facing * 4),
                    "post[" + facing + "] starts facing " + facing);
        }
        assertEquals(STRATEGY.getRunVertexEnd(rowPointer, ModelQuadFacing.COUNT - 1, PRIMITIVE_TYPE),
                mirror.getInt(base + SectionMetaBuffer.OFFSET_POSTS + ModelQuadFacing.COUNT * 4),
                "the last post closes the vertex range of the last facing");
        assertEquals(STRATEGY.getIndexOffset(rowPointer, 0) / 4,
                mirror.getInt(base + SectionMetaBuffer.OFFSET_FIRST_INDEX_BASE),
                "firstIndexBase is the section's index base in elements");

        for (int padding = 52; padding < SectionMetaBuffer.BYTES_PER_SECTION; padding += Integer.BYTES) {
            assertEquals(0, mirror.getInt(base + padding), "padding word at byte " + padding + " stays cleared");
        }
    }

    @Test
    void repushingASectionKeepsItsSlot() {
        int localSectionIndex = LocalSectionIndex.pack(0, 0, 0);
        int firstSlot = push(0, 0, 0, 0, localSectionIndex, singleFacing(ModelQuadFacing.POS_X, 0));
        int highWaterMark = this.meta.getHighWaterMark();

        int secondSlot = push(0, 0, 0, 0, localSectionIndex, singleFacing(ModelQuadFacing.POS_X, 8));

        assertEquals(firstSlot, secondSlot, "a section's slot is stable across updates");
        assertEquals(highWaterMark, this.meta.getHighWaterMark(), "re-pushing a section allocates nothing");
        assertEquals(8, this.meta.getMirrorForReadOnly().getInt(firstSlot * SectionMetaBuffer.BYTES_PER_SECTION
                + SectionMetaBuffer.OFFSET_POSTS), "the slot carries the updated vertex fence post");
    }

    @Test
    void passesAndSectionsTakeDistinctSlots() {
        int localSectionIndex = LocalSectionIndex.pack(1, 2, 3);
        int solidSlot = push(0, 0, 0, 0, localSectionIndex, singleFacing(ModelQuadFacing.POS_X, 0));
        int translucentSlot = push(1, 0, 0, 0, localSectionIndex, singleFacing(ModelQuadFacing.POS_X, 8));
        int neighbourSlot = push(0, 0, 0, 0, LocalSectionIndex.pack(2, 2, 3), singleFacing(ModelQuadFacing.POS_X, 16));

        Set<Integer> slots = new HashSet<>();
        slots.add(solidSlot);
        slots.add(translucentSlot);
        slots.add(neighbourSlot);

        assertEquals(3, slots.size(), "a slot belongs to exactly one pass and section");
        assertEquals(solidSlot, push(0, 0, 0, 0, localSectionIndex, singleFacing(ModelQuadFacing.POS_X, 24)),
                "re-pushing the solid section keeps its own slot");
        assertEquals(translucentSlot, push(1, 0, 0, 0, localSectionIndex, singleFacing(ModelQuadFacing.POS_X, 32)),
                "re-pushing the translucent section keeps its own slot");
    }

    @Test
    void removalClearsTheSlotAndReleasesIt() {
        int removed = LocalSectionIndex.pack(0, 0, 0);
        int survivor = LocalSectionIndex.pack(1, 0, 0);
        int removedSlot = push(0, 0, 0, 0, removed, singleFacing(ModelQuadFacing.POS_X, 0));
        int survivorSlot = push(0, 0, 0, 0, survivor, singleFacing(ModelQuadFacing.POS_X, 8));
        int highWaterMark = this.meta.getHighWaterMark();

        this.meta.remove(0, 0, 0, 0, removed);

        ByteBuffer mirror = this.meta.getMirrorForReadOnly();
        int removedBase = removedSlot * SectionMetaBuffer.BYTES_PER_SECTION;
        for (int offset = 0; offset < SectionMetaBuffer.BYTES_PER_SECTION; offset += Integer.BYTES) {
            assertEquals(0, mirror.getInt(removedBase + offset), "a released slot is cleared at byte " + offset);
        }
        assertEquals(8, mirror.getInt(survivorSlot * SectionMetaBuffer.BYTES_PER_SECTION
                + SectionMetaBuffer.OFFSET_POSTS), "releasing one slot leaves the other sections in place");

        int reclaimedSlot = push(0, 0, 0, 0, LocalSectionIndex.pack(3, 0, 0),
                singleFacing(ModelQuadFacing.POS_X, 16));

        assertEquals(removedSlot, reclaimedSlot, "the next section reuses the released slot");
        assertEquals(highWaterMark, this.meta.getHighWaterMark(), "slot reuse does not grow the mirror");
    }

    @Test
    void removingASectionThatWasNeverPushedAllocatesNothing() {
        int survivor = LocalSectionIndex.pack(0, 0, 0);
        int survivorSlot = push(0, 0, 0, 0, survivor, singleFacing(ModelQuadFacing.POS_X, 0));

        this.meta.remove(0, 0, 0, 0, LocalSectionIndex.pack(7, 3, 7));

        assertEquals(survivorSlot, push(0, 0, 0, 0, survivor, singleFacing(ModelQuadFacing.POS_X, 0)),
                "removing an unknown section leaves the pushed slot in place");
    }

    @Test
    void refusesASectionWithoutMeshRow() {
        assertEquals(-1, this.meta.update(0, 0, 0, 0, LocalSectionIndex.pack(0, 0, 0), 0L, 0, STRATEGY,
                        PRIMITIVE_TYPE),
                "a section without a mesh row cannot be published");
        assertNull(this.meta.getMirrorForReadOnly(), "a refused update leaves the mirror unallocated");
    }

    @Test
    void sinkReceivesTheLiveSlotsAndRetriesARejection() {
        assertFalse(this.meta.syncIfDirty(mirror -> {
            throw new AssertionError("a clean buffer never reaches the sink");
        }), "a fresh buffer reports nothing to sync");

        push(0, 0, 0, 0, LocalSectionIndex.pack(0, 0, 0), singleFacing(ModelQuadFacing.POS_X, 0));
        push(1, 0, 0, 0, LocalSectionIndex.pack(0, 0, 0), singleFacing(ModelQuadFacing.POS_X, 8));

        assertFalse(this.meta.syncIfDirty(mirror -> false), "a refused upload reports no sync");
        assertTrue(this.meta.isDirty(), "a refused upload leaves the pending slots dirty");

        int[] seenLimit = new int[1];
        assertTrue(this.meta.syncIfDirty(mirror -> {
            seenLimit[0] = mirror.limit();
            return true;
        }), "the retried upload reports a sync");
        assertEquals(this.meta.getHighWaterMark() * SectionMetaBuffer.BYTES_PER_SECTION, seenLimit[0],
                "only the slots in use are offered for upload");
        assertFalse(this.meta.isDirty(), "an accepted upload clears the dirty flag");
        assertFalse(this.meta.syncIfDirty(mirror -> {
            throw new AssertionError("the accepted upload is not repeated");
        }), "an accepted upload leaves nothing pending");
    }

    @Test
    void resetDropsEverySlot() {
        push(0, 0, 0, 0, LocalSectionIndex.pack(0, 0, 0), singleFacing(ModelQuadFacing.POS_X, 0));
        push(1, 0, 0, 0, LocalSectionIndex.pack(1, 0, 0), singleFacing(ModelQuadFacing.POS_X, 8));

        this.meta.reset();

        assertEquals(0, this.meta.getHighWaterMark(), "reset returns the mirror to empty");
        assertNull(this.meta.getMirrorForReadOnly(), "reset frees the mirror");
        assertFalse(this.meta.isDirty(), "reset clears the pending upload");
        assertEquals(0, push(1, 0, 0, 0, LocalSectionIndex.pack(1, 0, 0),
                        singleFacing(ModelQuadFacing.POS_X, 8)),
                "reset makes every slot available from the start again");
    }

    private static Map<ModelQuadFacing, VertexRange> singleFacing(ModelQuadFacing facing, int vertexStart) {
        Map<ModelQuadFacing, VertexRange> ranges = new EnumMap<>(ModelQuadFacing.class);
        ranges.put(facing, new VertexRange(vertexStart, 8));
        return ranges;
    }

    private int push(int passIndex, int regionOriginX, int regionOriginY, int regionOriginZ, int localSectionIndex,
                     Map<ModelQuadFacing, VertexRange> ranges) {
        STRATEGY.writeMeshesAndSliceMask(this.heap, localSectionIndex, ranges.values().iterator().next().vertexStart(),
                0, ranges, PRIMITIVE_TYPE);

        return this.meta.update(passIndex, regionOriginX, regionOriginY, regionOriginZ, localSectionIndex,
                STRATEGY.heapPointer(this.heap, localSectionIndex),
                STRATEGY.getSliceMask(this.heap, localSectionIndex), STRATEGY, PRIMITIVE_TYPE);
    }
}
