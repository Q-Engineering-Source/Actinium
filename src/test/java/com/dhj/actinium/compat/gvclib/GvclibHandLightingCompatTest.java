package com.dhj.actinium.compat.gvclib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GvclibHandLightingCompatTest {
    @Test
    void rendersTheVanillaEventWhenNoShaderPackIsActive() {
        assertTrue(GvclibHandLightingCompat.shouldRenderFirstPersonGun(false, false));
    }

    @Test
    void rendersTheEventInsideTheIrisHandPass() {
        assertTrue(GvclibHandLightingCompat.shouldRenderFirstPersonGun(true, true));
    }

    @Test
    void skipsTheLaterVanillaDuplicateDuringShaderRendering() {
        assertFalse(GvclibHandLightingCompat.shouldRenderFirstPersonGun(true, false));
    }

    @Test
    void onlyTheOutermostScopeOwnsTheFrameLightmapState() {
        assertTrue(GvclibHandLightingCompat.beginFirstPersonModelLightmapScope(false));
        assertTrue(GvclibHandLightingCompat.isFirstPersonModelLightmapScopeActive());
        assertFalse(GvclibHandLightingCompat.beginFirstPersonModelLightmapScope(true));

        assertFalse(GvclibHandLightingCompat.endFirstPersonModelLightmapScope());
        assertTrue(GvclibHandLightingCompat.isFirstPersonModelLightmapScopeActive());
        assertFalse(GvclibHandLightingCompat.endFirstPersonModelLightmapScope());
        assertFalse(GvclibHandLightingCompat.isFirstPersonModelLightmapScopeActive());
    }

    @Test
    void restoresTheVanillaLightmapOnlyWhenTheOuterScopeManagedIt() {
        assertTrue(GvclibHandLightingCompat.beginFirstPersonModelLightmapScope(true));
        assertFalse(GvclibHandLightingCompat.beginFirstPersonModelLightmapScope(false));

        assertFalse(GvclibHandLightingCompat.endFirstPersonModelLightmapScope());
        assertTrue(GvclibHandLightingCompat.endFirstPersonModelLightmapScope());
        assertFalse(GvclibHandLightingCompat.isFirstPersonModelLightmapScopeActive());
    }

    @Test
    void rejectsAnUnmatchedScopeEnd() {
        assertThrows(
            IllegalStateException.class,
            GvclibHandLightingCompat::endFirstPersonModelLightmapScope
        );
    }
}
