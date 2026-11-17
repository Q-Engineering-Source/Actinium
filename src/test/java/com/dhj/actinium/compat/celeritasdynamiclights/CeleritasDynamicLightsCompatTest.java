package com.dhj.actinium.compat.celeritasdynamiclights;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import toni.sodiumdynamiclights.SodiumDynamicLights;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the merge the tile entity light path performs through the production bridge.
 *
 * <p>The bridge resolves {@code toni.sodiumdynamiclights.SodiumDynamicLights} by name, so these tests
 * run against the stand-in of that class in the test source set and exercise the same reflective
 * lookup and the same {@code getLightmapWithDynamicLight(BlockPos, int)} call the addon receives.</p>
 */
class CeleritasDynamicLightsCompatTest {

    private static final BlockPos POS = new BlockPos(3, 72, -8);

    @AfterEach
    void clearDynamicLightLevel() {
        SodiumDynamicLights.setDynamicLightLevel(0);
    }

    @Test
    void replacesTheBlockLightWithAHigherDynamicLevel() {
        SodiumDynamicLights.setDynamicLightLevel(12);

        assertEquals(15 << 20 | 12 << 4, CeleritasDynamicLightsCompat.mergeDynamicLight(POS, 15 << 20 | 3 << 4));
    }

    @Test
    void keepsABlockLightAboveTheDynamicLevel() {
        SodiumDynamicLights.setDynamicLightLevel(6);
        final int packed = 9 << 20 | 11 << 4;

        assertEquals(packed, CeleritasDynamicLightsCompat.mergeDynamicLight(POS, packed));
    }

    @Test
    void returnsThePackedLightUntouchedWithoutADynamicLevel() {
        SodiumDynamicLights.setDynamicLightLevel(0);
        final int packed = 15 << 20 | 3 << 4 | 7;

        assertEquals(packed, CeleritasDynamicLightsCompat.mergeDynamicLight(POS, packed));
    }

    @Test
    void mergingAnAlreadyMergedLightChangesNothing() {
        SodiumDynamicLights.setDynamicLightLevel(12);
        final int once = CeleritasDynamicLightsCompat.mergeDynamicLight(POS, 15 << 20 | 3 << 4);

        assertEquals(once, CeleritasDynamicLightsCompat.mergeDynamicLight(POS, once));
    }
}
