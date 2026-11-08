package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.VisibilityEncoding;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One lattice serves both passes of a frame, and the passes root their searches at cameras that can be several
 * sections apart — the shadow pass hands the terrain pass the viewport it captured a frame earlier. A window
 * prepared for only one of them leaves the other pass searching outside the addressable interior.
 */
class SectionLatticeMultiCameraWindowTest {
    private static final float SEARCH_DISTANCE = 256.0F;

    /**
     * Element size of a region's geometry arena for the compact quad format. These sections never upload geometry, so
     * the region never allocates the arena; the value only states which layout the region was configured for.
     */
    private static final int ARENA_STRIDE =
            CompactChunkVertex.VERTEX_FORMAT.getStride() * QuadPrimitiveType.TRIANGULATED.getVerticesPerPrimitive();

    @Test
    void windowPreparedForTwoCamerasSupportsASearchFromEither() {
        RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        RenderSection terrainRoot = createSection(region, 0, 0, 0);
        RenderSection shadowRoot = createSection(region, 40, 0, 40);

        SectionLattice lattice = new SectionLattice(0, 15, false, false);
        lattice.attach(terrainRoot);
        lattice.attach(shadowRoot);

        Viewport terrainViewport = viewportAt(terrainRoot);
        Viewport shadowViewport = viewportAt(shadowRoot);

        lattice.ensureWindowCovers(SEARCH_DISTANCE, terrainViewport.getChunkCoord(), shadowViewport.getChunkCoord());

        assertEquals(Set.of(terrainRoot.getSectionIndex()), visitedSections(lattice, terrainViewport, 1));
        assertEquals(Set.of(shadowRoot.getSectionIndex()), visitedSections(lattice, shadowViewport, 2));
    }

    @Test
    void singleCameraWindowSupportsASearchFromThatCamera() {
        RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        RenderSection root = createSection(region, 40, 0, 40);

        SectionLattice lattice = new SectionLattice(0, 15, false, false);
        lattice.attach(root);

        Viewport viewport = viewportAt(root);

        lattice.ensureWindowCovers(viewport.getChunkCoord(), SEARCH_DISTANCE);

        assertEquals(Set.of(root.getSectionIndex()), visitedSections(lattice, viewport, 1));
    }

    private static Set<Integer> visitedSections(SectionLattice lattice, Viewport viewport, int frame) {
        Set<Integer> visited = new HashSet<>();

        lattice.findVisible((latticeIndex, regionId, sectionIndex, chunkX, chunkY, chunkZ, compactMeta, visible) -> {
            assertEquals(0, regionId);
            visited.add(sectionIndex);
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
