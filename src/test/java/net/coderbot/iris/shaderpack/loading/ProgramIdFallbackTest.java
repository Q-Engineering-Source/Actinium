package net.coderbot.iris.shaderpack.loading;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramIdFallbackTest {
    @Test
    void terrainFallbackChainEndsAtBasic() {
        assertEquals(
            List.of(ProgramId.TerrainSolid, ProgramId.Terrain, ProgramId.TexturedLit, ProgramId.Textured, ProgramId.Basic),
            fallbackChain(ProgramId.TerrainSolid)
        );
    }

    @Test
    void specializedEntityAndWaterProgramsUseExpectedFallbacks() {
        assertEquals(
            List.of(ProgramId.EntitiesGlowing, ProgramId.Entities, ProgramId.TexturedLit, ProgramId.Textured, ProgramId.Basic),
            fallbackChain(ProgramId.EntitiesGlowing)
        );
        assertEquals(
            List.of(ProgramId.HandWater, ProgramId.Hand, ProgramId.TexturedLit, ProgramId.Textured, ProgramId.Basic),
            fallbackChain(ProgramId.HandWater)
        );
        assertEquals(
            List.of(ProgramId.DhWater, ProgramId.DhTerrain),
            fallbackChain(ProgramId.DhWater)
        );
    }

    @Test
    void translucentBlockProgramDoesNotOverrideVanillaBlendByDefault() {
        assertNull(ProgramId.BlockTrans.getBlendModeOverride());
    }

    @Test
    void everyFallbackChainIsFinite() {
        for (ProgramId id : ProgramId.values()) {
            List<ProgramId> chain = fallbackChain(id);
            assertEquals(chain.size(), chain.stream().distinct().count(), id + " has a fallback cycle");
            assertTrue(chain.size() <= ProgramId.values().length, id + " has an unbounded fallback chain");
        }
    }

    private static List<ProgramId> fallbackChain(ProgramId start) {
        java.util.ArrayList<ProgramId> chain = new java.util.ArrayList<>();
        ProgramId current = start;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getFallback().orElse(null);
        }
        return List.copyOf(chain);
    }
}
