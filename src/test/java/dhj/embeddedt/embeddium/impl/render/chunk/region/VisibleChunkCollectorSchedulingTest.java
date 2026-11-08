package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.ChunkUpdateType;
import dhj.embeddedt.embeddium.impl.render.chunk.PackedSectionMetadata;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.VisibleChunkCollector;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice.VisibilitySnapshot;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.VisibilityEncoding;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibleChunkCollectorSchedulingTest {
    /**
     * Element size of a region's geometry arena for the compact quad format. These sections never upload geometry, so
     * the region never allocates the arena; the value only states which layout the region was configured for.
     */
    private static final int ARENA_STRIDE =
            CompactChunkVertex.VERTEX_FORMAT.getStride() * QuadPrimitiveType.TRIANGULATED.getVerticesPerPrimitive();

    @Test
    void capsInitialBuildQueueAndReportsOverflow() {
        RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        SectionLattice lattice = createLattice();
        RenderSection first = attach(lattice, region, 0, 0, 0, ChunkUpdateType.INITIAL_BUILD);
        RenderSection second = attach(lattice, region, 1, 0, 0, ChunkUpdateType.INITIAL_BUILD);
        VisibleChunkCollector collector = new VisibleChunkCollector(lattice, 0, 1, 1, new Vector3i(8, 8, 8));
        VisibilitySnapshot window = captureWindow(lattice);

        visit(collector, window, first);
        visit(collector, window, second);

        collector.finishRebuildLists();
        var rebuildLists = collector.getRebuildLists();

        assertEquals(1, rebuildLists.byUpdateType().get(ChunkUpdateType.INITIAL_BUILD).size());
        assertTrue(rebuildLists.hasAdditionalUpdates());
        assertEquals(1, rebuildLists.queueOverflowCounts().get(ChunkUpdateType.INITIAL_BUILD));
    }

    @Test
    void keepsRebuildQueueWhenTargetIsZero() {
        RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        SectionLattice lattice = createLattice();
        RenderSection first = attach(lattice, region, 0, 0, 0, ChunkUpdateType.REBUILD);
        RenderSection second = attach(lattice, region, 1, 0, 0, ChunkUpdateType.REBUILD);
        VisibleChunkCollector collector = new VisibleChunkCollector(lattice, 0, 1, 0, new Vector3i(8, 8, 8));
        VisibilitySnapshot window = captureWindow(lattice);

        visit(collector, window, first);
        visit(collector, window, second);

        collector.finishRebuildLists();
        var rebuildLists = collector.getRebuildLists();

        assertEquals(2, rebuildLists.byUpdateType().get(ChunkUpdateType.REBUILD).size());
        assertFalse(rebuildLists.hasAdditionalUpdates());
        assertEquals(0, rebuildLists.queueOverflowCounts().getOrDefault(ChunkUpdateType.REBUILD, 0));
    }

    @Test
    void visibilitySnapshotRejectsEmptyAndUnvisitedCells() {
        SectionLattice lattice = createLattice();
        RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        RenderSection loaded = attach(lattice, region, 0, 0, 0, null);
        RenderSection unvisited = attach(lattice, region, 1, 0, 0, null);
        BuiltRenderSectionData data = new BuiltRenderSectionData();
        data.visibilityData = VisibilityEncoding.NULL;
        loaded.setInfo(data);
        VisibilitySnapshot snapshot = captureWindow(lattice);

        assertTrue(snapshot.isSectionVisible(loaded.getChunkX(), loaded.getChunkY(), loaded.getChunkZ(), 0));
        assertFalse(snapshot.isSectionVisible(unvisited.getChunkX(), unvisited.getChunkY(), unvisited.getChunkZ(), 0));
        assertFalse(snapshot.isSectionVisible(2, 0, 0, 0));
        assertFalse(snapshot.isSectionVisible(0, 0, 1, 0));
    }

    private static SectionLattice createLattice() {
        SectionLattice lattice = new SectionLattice(0, 1, false, false);
        lattice.ensureWindowCovers(new Vector3i(0, 0, 0), 0.0F);
        return lattice;
    }

    private static RenderSection attach(SectionLattice lattice, RenderRegion region,
                                        int x, int y, int z, ChunkUpdateType updateType) {
        RenderSection section = new RenderSection(region, x, y, z);
        if (updateType != null) {
            section.setPendingUpdate(updateType);
        }
        region.addSection(section);
        lattice.attach(section);
        return section;
    }

    private static VisibilitySnapshot captureWindow(SectionLattice lattice) {
        Viewport viewport = new Viewport(
                (minX, minY, minZ, maxX, maxY, maxZ) -> true,
                new Vector3d(8.0, 8.0, 8.0)
        );

        return lattice.findVisible((latticeIndex, regionId, sectionIndex, chunkX, chunkY, chunkZ, compactMeta, visible) -> {
        }, viewport, 0.0F, 1, true, true, 0);
    }

    private static void visit(VisibleChunkCollector collector, VisibilitySnapshot window, RenderSection section) {
        int latticeIndex = latticeIndex(window, section);
        int compactMeta = PackedSectionMetadata.toCompactMeta(section.getPackedMetadata());

        collector.visit(latticeIndex, section.getRegion().getId(), section.getSectionIndex(),
                section.getChunkX(), section.getChunkY(), section.getChunkZ(), compactMeta, true);
    }

    private static int latticeIndex(VisibilitySnapshot window, RenderSection section) {
        int localX = section.getChunkX() - window.baseX();
        int localY = section.getChunkY() - window.baseY();
        int localZ = section.getChunkZ() - window.baseZ();

        assertTrue(localX >= 1 && localX <= window.dimX() - 2);
        assertTrue(localY >= 1 && localY <= window.dimY() - 2);
        assertTrue(localZ >= 1 && localZ <= window.dimZ() - 2);

        return (localX * window.dimZ() + localZ) * window.dimY() + localY;
    }
}
