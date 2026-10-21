package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.VisibilityEncoding;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice.VisibilitySnapshot;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies full-height search behavior through the actual lattice traversal. */
class SectionLatticeVerticalCoverageTest {
    private static final int MIN_SECTION_Y = -32;
    private static final int MAX_SECTION_Y = 32;
    private static final float SEARCH_DISTANCE = 32.0F;

    @Test
    void searchesFullHeightFromBothOutsideWorldBoundariesForMainAndShadowPasses() {
        SectionLattice lattice = new SectionLattice(MIN_SECTION_Y, MAX_SECTION_Y, true, false);
        int numRegions = attachVerticalColumn(lattice);

        Viewport aboveWorld = viewportAtY(1024.0);
        Viewport belowWorld = viewportAtY(-1024.0);
        lattice.ensureWindowCovers(SEARCH_DISTANCE,
                aboveWorld.getChunkCoord(), belowWorld.getChunkCoord());

        Set<Integer> expectedY = new HashSet<>();
        for (int y = MIN_SECTION_Y; y < MAX_SECTION_Y; y++) {
            expectedY.add(y);
        }

        SearchResult highMain = searchMain(lattice, aboveWorld, numRegions, 1);
        assertEquals(expectedY, highMain.sectionY());
        assertTrue(highMain.snapshot().isSectionVisible(0, MIN_SECTION_Y, 0, 1));
        assertTrue(highMain.snapshot().isSectionVisible(0, MAX_SECTION_Y - 1, 0, 1));

        SearchResult highShadow = searchShadow(lattice, aboveWorld, numRegions, null, 2);
        assertEquals(expectedY, highShadow.sectionY());
        assertTrue(highShadow.snapshot().isSectionVisible(0, MIN_SECTION_Y, 0, 2));
        assertTrue(highShadow.snapshot().isSectionVisible(0, MAX_SECTION_Y - 1, 0, 2));

        SearchResult lowMain = searchMain(lattice, belowWorld, numRegions, 3);
        assertEquals(expectedY, lowMain.sectionY());

        SearchResult lowShadow = searchShadow(lattice, belowWorld, numRegions, new Vector3f(0.0F, 1.0F, 0.0F), 4);
        assertEquals(expectedY, lowShadow.sectionY());
        assertTrue(lowShadow.snapshot().isSectionVisible(0, MIN_SECTION_Y, 0, 4));
        assertTrue(lowShadow.snapshot().isSectionVisible(0, MAX_SECTION_Y - 1, 0, 4));
    }

    private static int attachVerticalColumn(SectionLattice lattice) {
        Map<Integer, RenderRegion> regions = new HashMap<>();
        int firstRegionY = Math.floorDiv(MIN_SECTION_Y, RenderRegion.REGION_HEIGHT);
        for (int sectionY = MIN_SECTION_Y; sectionY < MAX_SECTION_Y; sectionY++) {
            int regionY = Math.floorDiv(sectionY, RenderRegion.REGION_HEIGHT);
            int regionId = regionY - firstRegionY;
            RenderRegion region = regions.computeIfAbsent(regionId,
                    ignored -> new RenderRegion(0, regionY, 0, regionId, null));

            RenderSection section = new RenderSection(region, 0, sectionY, 0);
            BuiltRenderSectionData data = new BuiltRenderSectionData();
            data.visibilityData = VisibilityEncoding.EVERYTHING;
            section.setInfo(data);
            lattice.attach(section);
        }

        return regions.size();
    }

    private static SearchResult searchMain(SectionLattice lattice, Viewport viewport, int numRegions, int frame) {
        Set<Integer> sectionY = new HashSet<>();
        VisibilitySnapshot snapshot = lattice.findVisible(
                (latticeIndex, regionId, sectionIndex, chunkX, chunkY, chunkZ, compactMeta, visible) -> {
                    assertEquals(0, chunkX);
                    assertEquals(0, chunkZ);
                    sectionY.add(chunkY);
                },
                viewport,
                SEARCH_DISTANCE,
                numRegions,
                false,
                false,
                frame
        );

        return new SearchResult(sectionY, snapshot);
    }

    private static SearchResult searchShadow(SectionLattice lattice, Viewport viewport, int numRegions,
                                             Vector3f lightVector, int frame) {
        Set<Integer> sectionY = new HashSet<>();
        VisibilitySnapshot snapshot = lattice.findShadowVisible(
                (latticeIndex, regionId, sectionIndex, chunkX, chunkY, chunkZ, compactMeta, visible) -> {
                    assertEquals(0, chunkX);
                    assertEquals(0, chunkZ);
                    sectionY.add(chunkY);
                },
                viewport,
                SEARCH_DISTANCE,
                numRegions,
                lightVector,
                frame
        );

        return new SearchResult(sectionY, snapshot);
    }

    private static Viewport viewportAtY(double y) {
        return new Viewport((minX, minY, minZ, maxX, maxY, maxZ) -> true, new Vector3d(8.0, y, 8.0));
    }

    private record SearchResult(Set<Integer> sectionY, VisibilitySnapshot snapshot) {
    }
}
