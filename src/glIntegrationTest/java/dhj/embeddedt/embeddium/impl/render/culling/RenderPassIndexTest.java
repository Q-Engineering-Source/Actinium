package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The section mirror keys its slots by a small pass index, so the index has to survive the pass instances being
 * rebuilt every time the pipeline is configured, and two differently named passes must never share one.
 */
class RenderPassIndexTest extends GLStateManagerIntegrationFixture {
    @Test
    void indexIsStablePerPassName() {
        int solidIndex = RenderPassIndex.indexOf(pass("meta-index-solid"));
        int translucentIndex = RenderPassIndex.indexOf(pass("meta-index-translucent"));

        assertNotEquals(solidIndex, translucentIndex, "passes with different names take different indices");
        assertEquals(solidIndex, RenderPassIndex.indexOf(pass("meta-index-solid")),
                "a rebuilt pass with the same name keeps its index");
        assertEquals(translucentIndex, RenderPassIndex.indexOf(pass("meta-index-translucent")),
                "the sibling pass keeps its index too");
        assertTrue(solidIndex >= 0 && solidIndex < RenderPassIndex.MAX_PASSES,
                "the index fits the pass-bit budget of the slot key");
        assertTrue(translucentIndex >= 0 && translucentIndex < RenderPassIndex.MAX_PASSES,
                "the sibling index fits the pass-bit budget of the slot key");
    }

    private static TerrainRenderPass pass(String name) {
        return TerrainRenderPass.builder()
                .name(name)
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(new CompactChunkVertex())
                .build();
    }
}
