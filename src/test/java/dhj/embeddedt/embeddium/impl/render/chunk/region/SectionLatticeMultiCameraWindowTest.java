package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.VisibilityEncoding;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One lattice serves both passes of a frame, and the passes root their searches at cameras that can be several
 * sections apart — the shadow pass hands the terrain pass the viewport it captured a frame earlier. A window
 * prepared for only one of them leaves the other pass searching outside the addressable interior.
 */
class SectionLatticeMultiCameraWindowTest {
    private static final float SEARCH_DISTANCE = 256.0F;

    @Test
    void windowPreparedForTwoCamerasSupportsASearchFromEither() {
        RenderRegion region = TestRegions.create(0, 0, 0, 0);
        RenderSection terrainRoot = createSection(region, 0, 0, 0);
        RenderSection shadowRoot = createSection(region, 40, 0, 40);

        SectionLattice lattice = new SectionLattice(0, 15, false, false);
        lattice.attach(terrainRoot);
        lattice.attach(shadowRoot);

        Viewport terrainViewport = viewportAt(terrainRoot);
        Viewport shadowViewport = viewportAt(shadowRoot);

        lattice.ensureWindowCovers(SEARCH_DISTANCE, terrainViewport.getChunkCoord(), shadowViewport.getChunkCoord());

        assertEquals(Set.of(terrainRoot), visitedSections(lattice, terrainViewport, 1));
        assertEquals(Set.of(shadowRoot), visitedSections(lattice, shadowViewport, 2));
    }

    @Test
    void singleCameraWindowSupportsASearchFromThatCamera() {
        RenderRegion region = TestRegions.create(0, 0, 0, 0);
        RenderSection root = createSection(region, 40, 0, 40);

        SectionLattice lattice = new SectionLattice(0, 15, false, false);
        lattice.attach(root);

        Viewport viewport = viewportAt(root);

        lattice.ensureWindowCovers(viewport.getChunkCoord(), SEARCH_DISTANCE);

        assertEquals(Set.of(root), visitedSections(lattice, viewport, 1));
    }

    // Section identity rather than sectionIndex: the packed local index folds Y into two bits, so sections that
    // differ only in Y share it and a set of indices cannot tell which section a search reached.
    private static Set<RenderSection> visitedSections(SectionLattice lattice, Viewport viewport, int frame) {
        Set<RenderSection> visited = new HashSet<>();

        lattice.findVisible((latticeIndex, regionId, sectionIndex, chunkX, chunkY, chunkZ, compactMeta, visible) -> {
            assertEquals(0, regionId);

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
