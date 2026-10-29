package dhj.embeddedt.embeddium.impl.render.chunk.region;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that passes with different vertex formats share one region arena while each still draws through the
 * vertex array its own format requires.
 */
class RenderRegionSharedArenaTest {
    private static final int COMPACT_STRIDE = ChunkMeshFormats.COMPACT.getVertexFormat().getStride();
    private static final int VANILLA_LIKE_STRIDE = ChunkMeshFormats.VANILLA_LIKE.getVertexFormat().getStride();

    @Test
    void tessellationKeySeparatesPassesThatInterpretTheArenaDifferently() {
        TerrainRenderPass compactSolid = pass("compact_solid", ChunkMeshFormats.COMPACT, false);
        TerrainRenderPass widerSolid = pass("wider_solid", ChunkMeshFormats.VANILLA_LIKE, false);
        TerrainRenderPass compactCopy = pass("compact_copy", ChunkMeshFormats.COMPACT, false);

        assertNotEquals(compactSolid.tessellationKey(), widerSolid.tessellationKey(),
                "a pass with a different vertex format needs a different vertex array");
        assertEquals(compactSolid.tessellationKey(), compactCopy.tessellationKey(),
                "passes that read the arena identically can share one vertex array");

        TerrainRenderPass compactSorted = pass("compact_translucent", ChunkMeshFormats.COMPACT, true);
        assertNotEquals(compactSolid.tessellationKey(), compactSorted.tessellationKey(),
                "a sorted pass draws from its own index buffer, so its element binding differs");
    }

    @Test
    void commonStrideIsDivisibleByEveryDeclaredVertexStride() {
        int stride = RenderRegionManager.computeCommonVertexStride(configuration(ChunkMeshFormats.COMPACT,
                ChunkMeshFormats.VANILLA_LIKE));

        assertEquals(0, stride % COMPACT_STRIDE, "the " + COMPACT_STRIDE + " byte format must divide " + stride);
        assertEquals(0, stride % VANILLA_LIKE_STRIDE,
                "the " + VANILLA_LIKE_STRIDE + " byte format must divide " + stride);
        assertTrue(stride >= Math.max(COMPACT_STRIDE, VANILLA_LIKE_STRIDE),
                "the shared stride cannot be narrower than the widest format it serves");
    }

    @Test
    void commonStrideOfOneFormatIsThatFormatAlone() {
        assertEquals(COMPACT_STRIDE,
                RenderRegionManager.computeCommonVertexStride(configuration(ChunkMeshFormats.COMPACT)));
    }

    @Test
    void regionReportsTheStrideItAllocatesIn() {
        assertEquals(96, new RenderRegion(0, 0, 0, 0, null, 96).getCommonVertexStride());
    }

    @Test
    void regionWithoutArenaStrideRefusesToCreateDeviceResources() {
        RenderRegion region = new RenderRegion(0, 0, 0, 0, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> region.createResources(null));
        assertTrue(failure.getMessage().contains("no arena stride"), failure.getMessage());
    }

    @Test
    void resourcesAreAbsentUntilTheFirstUpload() {
        assertNull(new RenderRegion(0, 0, 0, 0, null, 96).getResources(),
                "a region allocates no GPU storage before its first mesh");
    }

    private static TerrainRenderPass pass(String name, ChunkVertexType vertexType, boolean sorted) {
        return TerrainRenderPass.builder()
                .name(name)
                .useReverseOrder(sorted)
                .useTranslucencySorting(sorted)
                .fragmentDiscard(false)
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(vertexType)
                .build();
    }

    /** Builds a configuration whose only stage declares one pass per supplied vertex type. */
    private static RenderPassConfiguration<String> configuration(ChunkVertexType... vertexTypes) {
        Map<String, Material> materials = new LinkedHashMap<>();
        Map<String, Collection<TerrainRenderPass>> stages = new LinkedHashMap<>();
        Material first = null;

        for (int index = 0; index < vertexTypes.length; index++) {
            TerrainRenderPass pass = pass("pass_" + index, vertexTypes[index], false);
            Material material = new Material(pass, AlphaCutoffParameter.ZERO, false);
            String stage = "stage_" + index;

            materials.put(stage, material);
            stages.put(stage, List.of(pass));

            if (first == null) {
                first = material;
            }
        }

        return new RenderPassConfiguration<>(materials, stages, first, first, first);
    }
}
