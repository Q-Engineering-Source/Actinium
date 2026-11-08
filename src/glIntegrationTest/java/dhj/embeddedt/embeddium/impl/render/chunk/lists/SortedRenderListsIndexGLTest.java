package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegionManager;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that a render list can be addressed by its position in the iteration order, which is how the compute cull
 * walk visits regions without allocating an iterator. The list contents come from real regions because the
 * constructor is package-private and the region passes decide which render lists are non-empty.
 */
class SortedRenderListsIndexGLTest extends GLStateManagerIntegrationFixture {
    private CommandList commandList;
    private RenderRegionManager regionManager;

    @BeforeEach
    void setUp() {
        RenderDevice.INSTANCE.makeActive();
        this.commandList = RenderDevice.INSTANCE.createCommandList();
        this.regionManager = new RenderRegionManager(this.commandList, configuration());
    }

    @AfterEach
    void tearDown() {
        try {
            this.regionManager.delete(this.commandList);
        } finally {
            this.commandList.close();
            RenderDevice.INSTANCE.makeInactive();
        }
    }

    @Test
    void indexedAccessFollowsIterationOrder() {
        ChunkRenderList first = new ChunkRenderList(this.regionManager.createForChunk(0, 0, 0));
        ChunkRenderList second = new ChunkRenderList(this.regionManager.createForChunk(8, 0, 0));
        ObjectArrayList<ChunkRenderList> lists = new ObjectArrayList<>();
        lists.add(first);
        lists.add(second);
        SortedRenderLists sorted = new SortedRenderLists(lists);

        assertEquals(2, sorted.getNumRegions(), "both regions are part of the list");
        assertSame(first, sorted.getRegion(0), "index 0 is the first region of the list");
        assertSame(second, sorted.getRegion(1), "index 1 is the second region of the list");

        Iterator<ChunkRenderList> iterator = sorted.iterator();
        assertSame(sorted.getRegion(0), iterator.next(), "the forward iteration starts at index 0");
        assertSame(sorted.getRegion(1), iterator.next(), "the forward iteration ends at the last index");
    }

    @Test
    void missingIndexThrows() {
        ObjectArrayList<ChunkRenderList> lists = new ObjectArrayList<>();
        lists.add(new ChunkRenderList(this.regionManager.createForChunk(0, 0, 0)));
        SortedRenderLists sorted = new SortedRenderLists(lists);

        assertThrows(IndexOutOfBoundsException.class, () -> sorted.getRegion(1),
                "an index past the end of the list is rejected");
        assertThrows(IndexOutOfBoundsException.class, () -> sorted.getRegion(-1),
                "a negative index is rejected");
        assertThrows(IndexOutOfBoundsException.class, () -> SortedRenderLists.empty().getRegion(0),
                "an empty list has no region at index 0");
    }

    private static RenderPassConfiguration<Object> configuration() {
        TerrainRenderPass solid = TerrainRenderPass.builder()
                .name("list-index-solid")
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(new CompactChunkVertex())
                .build();
        Material material = new Material(solid, AlphaCutoffParameter.ZERO, true);

        return new RenderPassConfiguration<>(Map.of(), Map.of(), material, material, material);
    }
}
