package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.GenericSectionSpriteTicker;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A section only records which sprites its baked mesh sampled. The sprite ticker replays that record for every
 * rendered frame, so a cached mesh keeps its textures animating while a sprite shared by many sections or regions is
 * notified once per frame.
 */
class SectionSpriteReplayTest {

    /**
     * Element size of a region's geometry arena for the compact quad format. These sections never upload geometry, so
     * the region never allocates the arena; the value only states which layout the region was configured for.
     */
    private static final int ARENA_STRIDE =
            CompactChunkVertex.VERTEX_FORMAT.getStride() * QuadPrimitiveType.TRIANGULATED.getVerticesPerPrimitive();

    @Test
    void spriteSharedBySeveralSectionsIsNotifiedOncePerFrame() {
        final Object stone = new Object();
        final Object dirt = new Object();
        final RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        final RenderSection first = sectionWithSprites(region, 0, 0, 0, stone, dirt);
        final RenderSection second = sectionWithSprites(region, 1, 0, 0, stone);
        final ChunkRenderList renderList = renderListOf(region, first, second);

        final List<Object> notified = new ArrayList<>();
        final GenericSectionSpriteTicker<Object> ticker = new GenericSectionSpriteTicker<>(notified::add);
        ticker.onRenderListUpdated(List.of(renderList));

        ticker.tickVisibleRenders();

        assertEquals(1, countOf(notified, stone), "a sprite used by two sections is notified once");
        assertEquals(1, countOf(notified, dirt), "every sprite the mesh sampled is notified");
    }

    @Test
    void cachedMeshNotifiesItsSpritesOnEveryFrame() {
        final Object stone = new Object();
        final RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        final ChunkRenderList renderList = renderListOf(region, sectionWithSprites(region, 0, 0, 0, stone));

        final List<Object> notified = new ArrayList<>();
        final GenericSectionSpriteTicker<Object> ticker = new GenericSectionSpriteTicker<>(notified::add);
        ticker.onRenderListUpdated(List.of(renderList));

        for (int frame = 0; frame < 3; frame++) {
            notified.clear();
            ticker.tickVisibleRenders();
            assertEquals(1, countOf(notified, stone), "frame " + frame);
        }
    }

    @Test
    void rebuiltSectionReplacesTheSpritesOfItsPreviousMesh() {
        final Object stone = new Object();
        final Object gravel = new Object();
        final RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        final RenderSection section = sectionWithSprites(region, 0, 0, 0, stone);
        final ChunkRenderList renderList = renderListOf(region, section);

        final List<Object> notified = new ArrayList<>();
        final GenericSectionSpriteTicker<Object> ticker = new GenericSectionSpriteTicker<>(notified::add);
        ticker.onRenderListUpdated(List.of(renderList));

        section.setInfo(sectionData(gravel));
        ticker.onRenderListUpdated(List.of(renderList));
        ticker.tickVisibleRenders();

        assertEquals(0, countOf(notified, stone), "the sprite of the replaced mesh is no longer notified");
        assertEquals(1, countOf(notified, gravel), "the sprite of the new mesh is notified");
    }

    @Test
    void renderListWithoutSpriteSourcesStopsNotifyingThePreviousFrame() {
        final Object stone = new Object();
        final RenderRegion region = new RenderRegion(0, 0, 0, 0, null, ARENA_STRIDE);
        final ChunkRenderList renderList = renderListOf(region, sectionWithSprites(region, 0, 0, 0, stone));

        final List<Object> notified = new ArrayList<>();
        final GenericSectionSpriteTicker<Object> ticker = new GenericSectionSpriteTicker<>(notified::add);
        ticker.onRenderListUpdated(List.of(renderList));

        final RenderRegion plainRegion = new RenderRegion(0, 0, 1, 1, null, ARENA_STRIDE);
        final RenderSection plainSection = new RenderSection(plainRegion, 0, 0, 8);
        plainRegion.addSection(plainSection);
        plainSection.setInfo(new MinecraftBuiltRenderSectionData<>());
        final ChunkRenderList plainList = renderListOf(plainRegion, plainSection);
        assertEquals(0, plainList.getSectionsWithSpritesCount(), "a section without sprites is not a sprite source");
        ticker.onRenderListUpdated(List.of(plainList));

        ticker.tickVisibleRenders();

        assertTrue(notified.isEmpty(), "the sprite of the previous frame is not notified again");
    }

    private static RenderSection sectionWithSprites(RenderRegion region, int x, int y, int z, Object... sprites) {
        final RenderSection section = new RenderSection(region, x, y, z);
        region.addSection(section);
        section.setInfo(sectionData(sprites));
        return section;
    }

    private static MinecraftBuiltRenderSectionData<Object, Object> sectionData(Object... sprites) {
        final MinecraftBuiltRenderSectionData<Object, Object> data = new MinecraftBuiltRenderSectionData<>();
        for (Object sprite : sprites) {
            data.animatedSprites.add(sprite);
        }
        data.bake();
        return data;
    }

    private static ChunkRenderList renderListOf(RenderRegion region, RenderSection... sections) {
        final ChunkRenderList renderList = new ChunkRenderList(region);
        for (RenderSection section : sections) {
            renderList.add(section);
        }
        return renderList;
    }

    private static int countOf(List<Object> notified, Object sprite) {
        int count = 0;
        for (Object entry : notified) {
            if (entry == sprite) {
                count++;
            }
        }
        return count;
    }
}
