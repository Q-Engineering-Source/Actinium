package dhj.embeddedt.embeddium.impl.render.chunk.occlusion;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.TestRegions;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lattice window is an index into the attached sections, and the shadow search seeds itself from the cells the
 * terrain search reported visible. Moving the window therefore invalidates both the placement of those cells and the
 * packed local coordinates stored beside them, and the world height must not force the window to allocate the whole
 * vertical extent.
 */
class SectionLatticeWindowTest {
    private static final float SEARCH_DISTANCE = 32.0F;

    // Vertical span attached for the two-camera case: the two camera sections, the section below the lower camera and
    // the section above the upper camera.
    private static final int SPAN_BOTTOM_Y = 6;
    private static final int LOWER_CAMERA_Y = 8;
    private static final int UPPER_CAMERA_Y = 18;
    private static final int SPAN_TOP_Y = 20;

    private static final int[][] ROOTS = {
            {2, 8, 7}, {3, 9, 7},
            {8, 8, 7}, {9, 8, 6}, {10, 10, 8}, {13, 5, 9}, {14, 8, 7},
    };

    // Every row moves the window away from the initial camera section (8, 8, 7). The first three are the upstream
    // single-axis moves, the next two move two axes in the positive direction, and the last three move at least one
    // axis in the negative direction, where the slide has to read the retained range from the other end.
    @ParameterizedTest
    @CsvSource({
            "14, 8, 7, 5",
            "8, 14, 7, 6",
            "40, 8, 7, 0",
            "14, 14, 7, 4",
            "8, 14, 14, 1",
            "2, 2, 7, 2",
            "8, 14, 0, 1",
            "8, 2, 7, 5",
    })
    void windowMoveKeepsVisibleCellsOnTheirSections(int cameraX, int cameraY, int cameraZ, int retained) {
        SectionLattice lattice = new SectionLattice(0, 16, true, false);
        Map<Long, RenderRegion> regions = new HashMap<>();

        for (int x = 0; x <= 30; x++) {
            for (int y = 0; y <= 15; y++) {
                for (int z = 0; z <= 14; z++) {
                    int rx = x >> RenderRegion.REGION_WIDTH_SH;
                    int ry = y >> RenderRegion.REGION_HEIGHT_SH;
                    int rz = z >> RenderRegion.REGION_LENGTH_SH;
                    RenderRegion region = regions.computeIfAbsent(RenderRegion.key(rx, ry, rz),
                            key -> TestRegions.create(rx, ry, rz, regions.size()));
                    lattice.attach(createSection(region, x, y, z));
                }
            }
        }

        lattice.ensureWindowCovers(new Vector3i(8, 8, 7), SEARCH_DISTANCE);

        long[] cells = lattice.ensureVisibleCellsCapacity(ROOTS.length);
        for (int i = 0; i < ROOTS.length; i++) {
            int[] root = ROOTS[i];
            int idx = lattice.indexOf(root[0], root[1], root[2]);
            cells[i] = ((long) lattice.packXyz(root[0], root[1], root[2]) << 32) | (idx & 0xFFFFFFFFL);
        }
        lattice.visibleCount = ROOTS.length;

        lattice.ensureWindowCovers(new Vector3i(cameraX, cameraY, cameraZ), SEARCH_DISTANCE);

        Set<RenderSection> expected = new HashSet<>();
        for (int[] root : ROOTS) {
            int idx = lattice.indexOf(root[0], root[1], root[2]);
            if (idx >= 0) {
                expected.add(lattice.sectionAt(idx));
            }
        }
        assertEquals(retained, expected.size());
        assertEquals(retained, lattice.visibleCount);

        Set<RenderSection> seen = new HashSet<>();
        for (int i = 0; i < lattice.visibleCount; i++) {
            long entry = lattice.visibleCells[i];
            RenderSection section = lattice.sectionAt((int) entry);
            assertTrue(expected.contains(section));
            assertTrue(seen.add(section));
            assertEquals(lattice.packXyz(section.getChunkX(), section.getChunkY(), section.getChunkZ()),
                    (int) (entry >>> 32));
        }
    }

    @Test
    void twoCamerasKeepTheirWholeVerticalSpanSearchable() {
        SectionLattice lattice = new SectionLattice(0, 255, false, false);
        RenderRegion region = TestRegions.create(0, 0, 0, 0);

        // The traversal steps only into installed cells, so a section further away from the camera is reachable only
        // through the sections between them. The span the two cameras must hold is therefore attached as one connected
        // column, which extends past both camera sections.
        Map<Integer, RenderSection> column = new HashMap<>();

        for (int y = SPAN_BOTTOM_Y; y <= SPAN_TOP_Y; y++) {
            RenderSection section = createSection(region, 8, y, 7);
            lattice.attach(section);
            column.put(y, section);
        }

        Viewport lowerViewport = viewportAt(column.get(LOWER_CAMERA_Y));
        Viewport upperViewport = viewportAt(column.get(UPPER_CAMERA_Y));

        lattice.ensureWindowCovers(SEARCH_DISTANCE, lowerViewport.getChunkCoord(), upperViewport.getChunkCoord());

        Set<RenderSection> span = new HashSet<>(column.values());

        // Both searches must reach the whole span: a window that leaves the two end sections out of its vertical
        // interior drops them from both searches.
        assertEquals(span, visitedSections(lattice, lowerViewport, 1));
        assertEquals(span, visitedSections(lattice, upperViewport, 2));
    }

    @Test
    void tallWorldSearchesNearTheCameraWithoutAllocatingItsWholeHeight() {
        SectionLattice lattice = assertDoesNotThrow(() -> new SectionLattice(-1024, 1023, true, false));
        RenderRegion region = TestRegions.create(0, 0, 0, 0);
        RenderSection root = createSection(region, 0, 0, 0);
        lattice.attach(root);

        Viewport viewport = viewportAt(root);
        lattice.ensureWindowCovers(viewport.getChunkCoord(), SEARCH_DISTANCE);

        assertEquals(Set.of(root), visitedSections(lattice, viewport, 1));
    }

    private static Set<RenderSection> visitedSections(SectionLattice lattice, Viewport viewport, int frame) {
        Set<RenderSection> visited = new HashSet<>();

        lattice.findVisible((latticeIndex, regionId, sectionIndex, chunkX, chunkY, chunkZ, compactMeta, visible) -> {
            RenderSection section = lattice.sectionAt(latticeIndex);
            assertNotNull(section);
            visited.add(section);
        }, viewport, SEARCH_DISTANCE, 1, true, true, frame);

        return visited;
    }

    private static Viewport viewportAt(RenderSection section) {
        return new Viewport((minX, minY, minZ, maxX, maxY, maxZ) -> true,
                new Vector3d(section.getChunkX() * 16 + 8, section.getChunkY() * 16 + 8, section.getChunkZ() * 16 + 8));
    }

    private static RenderSection createSection(RenderRegion region, int x, int y, int z) {
        RenderSection section = new RenderSection(region, x, y, z);
        BuiltRenderSectionData data = new BuiltRenderSectionData();
        data.visibilityData = VisibilityEncoding.EVERYTHING;
        section.setInfo(data);
        return section;
    }
}
