package com.dhj.actinium.compat.hbm;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HbmRenderStateCompatTest {
    @Test
    void mapsHbmDepthScopeToGlsmDepthAndShadeState() {
        assertEquals(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT,
            HbmRenderStateCompat.toGlMask(0x00100));
    }

    @Test
    void mapsHbmGuiScopeToEquivalentGlsmGroups() {
        assertEquals(GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT,
            HbmRenderStateCompat.toGlMask(0x46000));
    }

    @Test
    void mapsHbmColorScopeToCurrentColorState() {
        assertEquals(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT,
            HbmRenderStateCompat.toGlMask(0x04000));
    }

    @Test
    void mapsHbmAllBitsToEveryMappedGlsmGroup() {
        assertEquals(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_POLYGON_BIT
                | GL11.GL_FOG_BIT,
            HbmRenderStateCompat.toGlMask(0xFFFFF));
    }

    @Test
    void ignoresHbmBitsWithoutAGlsmGroup() {
        // Issue #170: NTM-Space's Stardar GUI pushes 0x4, a bit neither HBM's capture chain nor this
        // mapping knows. HBM captures nothing for it, so the scope must still open instead of
        // aborting the client; shade model alone still has to be saved.
        assertEquals(GL11.GL_LIGHTING_BIT, HbmRenderStateCompat.toGlMask(0x4));
    }

    @Test
    void mapsTheMappedBitsOfAMaskCarryingUnknownOnes() {
        // Issue #170 reported bits for the same scope (0x4 from the reporter, 0x6004 in NTM-Space
        // 0.9.2's bytecode). Both resolve to the groups of their mapped bits.
        assertEquals(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
                | GL11.GL_LIGHTING_BIT,
            HbmRenderStateCompat.toGlMask(0x6004));
    }

    @Test
    void ignoresUnknownHbmBitsOutsideTheAllSentinel() {
        // 0x100000 is above HBM_ALL_BITS, so it never takes the "capture everything" path.
        assertEquals(GL11.GL_LIGHTING_BIT, HbmRenderStateCompat.toGlMask(0x100000));
    }

    @Test
    void splitsCombinedLightUsingVanillaPacking() {
        int combinedLight = 0xABCD1234;

        assertEquals(0x1234, HbmRenderStateCompat.blockLight(combinedLight));
        assertEquals(0xABCD, HbmRenderStateCompat.skyLight(combinedLight));
    }
}
