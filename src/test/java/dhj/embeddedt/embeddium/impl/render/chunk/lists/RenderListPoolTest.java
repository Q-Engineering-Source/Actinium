package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.TestRegions;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.impl.render.viewport.frustum.Frustum;
import dhj.embeddedt.embeddium.impl.util.iterator.ByteIterator;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reuse of {@link ChunkRenderList} containers: the reset that forgets a previous search,
 * the two rotating slots of {@link RenderListPool}, and the collector wiring that keeps a published
 * render list readable while the next search fills the other slot.
 */
class RenderListPoolTest {
    private static final int REGION_COUNT = 4;
    private static final int GEOMETRY = 1 << RenderVisualsService.HAS_BLOCK_GEOMETRY;
    private static final int ALL_FLAGS = GEOMETRY
            | (1 << RenderVisualsService.HAS_SPRITES)
            | (1 << RenderVisualsService.HAS_BLOCK_ENTITIES)
            | (1 << RenderVisualsService.NEEDS_DYNAMIC_SORT);

    private static final float SEARCH_DISTANCE = 256.0f;
    private static final int CAMERA_SECTION_X = 0;
    private static final int CAMERA_SECTION_Y = 5;
    private static final int CAMERA_SECTION_Z = 0;

    // Every box is visible: the tests are about container lifecycle, not culling.
    private static final Frustum ALWAYS_VISIBLE = (minX, minY, minZ, maxX, maxY, maxZ) -> true;

    @Test
    void resetClearsEveryCountAndKeepsTheRegion() {
        RenderRegion region = TestRegions.create(0);
        ChunkRenderList list = new ChunkRenderList(region);

        list.add(3, ALL_FLAGS);
        list.add(7, GEOMETRY);

        assertEquals(2, list.size());
        assertEquals(2, list.getSectionsWithGeometryCount());
        assertEquals(1, list.getSectionsWithSpritesCount());
        assertEquals(1, list.getSectionsWithEntitiesCount());
        assertTrue(list.sectionsNeedingDynamicSortIterator().hasNext());

        list.reset();

        assertEquals(0, list.size());
        assertEquals(0, list.getSectionsWithGeometryCount());
        assertEquals(0, list.getSectionsWithSpritesCount());
        assertEquals(0, list.getSectionsWithEntitiesCount());
        assertNull(list.sectionsWithGeometryIterator());
        assertNull(list.sectionsWithSpritesIterator());
        assertNull(list.sectionsWithEntitiesIterator());
        assertNull(list.sectionsNeedingDynamicSortIterator());
        assertSame(region, list.getRegion());
    }

    @Test
    void resetLetsTheSameListBeFilledAgainWithoutKeepingOldSections() {
        ChunkRenderList list = new ChunkRenderList(TestRegions.create(0));

        list.add(3, GEOMETRY);
        list.reset();
        list.add(5, GEOMETRY);

        ByteIterator iterator = list.sectionsWithGeometryIterator();
        assertEquals(5, iterator.nextByteAsInt());
        assertFalse(iterator.hasNext());
        assertEquals(1, list.size());
    }

    @Test
    void beginSearchAlternatesTwoSlotsAndKeepsThePublishedListsReadable() {
        RenderListPool pool = new RenderListPool();
        RenderRegion region = TestRegions.create(0);

        RenderListPool.Slot firstSlot = pool.beginSearch(REGION_COUNT);
        ChunkRenderList firstList = firstSlot.acquire(region);
        firstList.add(1, GEOMETRY);
        SortedRenderLists published = new SortedRenderLists(firstSlot.lists());

        RenderListPool.Slot secondSlot = pool.beginSearch(REGION_COUNT);

        assertNotSame(firstSlot.lists(), secondSlot.lists());
        assertEquals(0, secondSlot.lists().size());
        assertEquals(1, published.getNumRegions());
        assertSame(firstList, published.getRegion(0));
        assertEquals(1, published.getRegion(0).getSectionsWithGeometryCount());

        ChunkRenderList secondList = secondSlot.acquire(region);
        secondList.add(2, GEOMETRY);

        assertNotSame(firstList, secondList);
        assertEquals(1, firstList.getSectionsWithGeometryCount());
        assertEquals(1, secondList.getSectionsWithGeometryCount());
    }

    @Test
    void reusingASlotResetsTheRegionListInsteadOfStackingSections() {
        RenderListPool pool = new RenderListPool();
        RenderRegion region = TestRegions.create(0);

        RenderListPool.Slot firstSlot = pool.beginSearch(REGION_COUNT);
        ChunkRenderList list = firstSlot.acquire(region);
        list.add(1, GEOMETRY);
        list.add(2, GEOMETRY);

        pool.beginSearch(REGION_COUNT);
        RenderListPool.Slot reusedSlot = pool.beginSearch(REGION_COUNT);

        assertSame(firstSlot, reusedSlot);
        assertEquals(0, reusedSlot.lists().size());

        ChunkRenderList reusedList = reusedSlot.acquire(region);

        assertSame(list, reusedList);
        assertEquals(0, reusedList.size());
        assertEquals(0, reusedList.getSectionsWithGeometryCount());
    }

    @Test
    void acquireCreatesANewListWhenTheRegionInstanceChanged() {
        RenderListPool pool = new RenderListPool();
        RenderListPool.Slot slot = pool.beginSearch(REGION_COUNT);

        ChunkRenderList first = slot.acquire(TestRegions.create(0));
        first.add(1, GEOMETRY);

        ChunkRenderList replacement = slot.acquire(TestRegions.create(0));

        assertNotSame(first, replacement);
        assertEquals(0, replacement.size());
        assertEquals(1, first.getSectionsWithGeometryCount());
    }

    @Test
    void searchThroughTheCollectorFillsOnlyItsOwnSlot() {
        SectionLattice lattice = new SectionLattice(0, 15, false, false);
        Vector3i cameraSection = new Vector3i(CAMERA_SECTION_X, CAMERA_SECTION_Y, CAMERA_SECTION_Z);
        lattice.ensureWindowCovers(SEARCH_DISTANCE, cameraSection);

        RenderRegion region = TestRegions.create(0);
        RenderSection section = new RenderSection(region, CAMERA_SECTION_X, CAMERA_SECTION_Y, CAMERA_SECTION_Z);
        BuiltRenderSectionData data = new BuiltRenderSectionData();
        data.hasBlockGeometry = true;
        section.setInfo(data);
        lattice.attach(section);

        Viewport viewport = new Viewport(ALWAYS_VISIBLE, new Vector3d(
                (CAMERA_SECTION_X << 4) + 8.0,
                (CAMERA_SECTION_Y << 4) + 8.0,
                (CAMERA_SECTION_Z << 4) + 8.0));

        RenderListPool pool = new RenderListPool();

        VisibleChunkCollector first = search(lattice, pool, viewport, 1);
        assertEquals(1, first.getSortedRenderLists().size());
        ChunkRenderList firstList = first.getSortedRenderLists().get(0);
        assertEquals(1, firstList.getSectionsWithGeometryCount());
        assertSame(section, sectionOf(firstList, section));

        VisibleChunkCollector second = search(lattice, pool, viewport, 2);
        assertEquals(1, second.getSortedRenderLists().size());
        ChunkRenderList secondList = second.getSortedRenderLists().get(0);

        assertNotSame(first.getSortedRenderLists(), second.getSortedRenderLists());
        assertNotSame(firstList, secondList);
        assertEquals(1, firstList.getSectionsWithGeometryCount());
        assertEquals(1, secondList.getSectionsWithGeometryCount());

        VisibleChunkCollector third = search(lattice, pool, viewport, 3);

        assertEquals(1, third.getSortedRenderLists().size());
        assertSame(firstList, third.getSortedRenderLists().get(0));
        assertEquals(1, third.getSortedRenderLists().get(0).getSectionsWithGeometryCount());
        assertSame(section, sectionOf(third.getSortedRenderLists().get(0), section));
    }

    private static VisibleChunkCollector search(SectionLattice lattice, RenderListPool pool, Viewport viewport, int frame) {
        VisibleChunkCollector collector = new VisibleChunkCollector(lattice, pool.beginSearch(REGION_COUNT), frame, 0,
                viewport.getBlockCoord());
        lattice.findVisible(collector, viewport, SEARCH_DISTANCE, REGION_COUNT, false, true, frame);
        return collector;
    }

    // The list stores local section indices, so the expected section is the one occupying that
    // index inside the list's region. The test lattice holds exactly one attached section.
    private static RenderSection sectionOf(ChunkRenderList list, RenderSection expected) {
        ByteIterator iterator = list.sectionsWithGeometryIterator();
        assertEquals(1, list.getSectionsWithGeometryCount());
        int sectionIndex = iterator.nextByteAsInt();
        assertFalse(iterator.hasNext());
        assertEquals(expected.getSectionIndex(), sectionIndex);
        return expected;
    }
}
