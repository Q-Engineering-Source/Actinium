package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipCullDistanceSupportTest {

    @Test
    void desktopGlAlwaysSupportsClipDistance() {
        // gl_ClipDistance is core in desktop GLSL 130+; our shaders are emitted as 330 core.
        assertTrue(RenderSystem.resolveClipCullDistanceSupport(false, false, false));
        assertTrue(RenderSystem.resolveClipCullDistanceSupport(true, false, false));
    }

    @Test
    void glesRequiresTheExtension() {
        assertFalse(RenderSystem.resolveClipCullDistanceSupport(false, true, false),
            "ES without GL_EXT_clip_cull_distance cannot compile gl_ClipDistance");
        assertTrue(RenderSystem.resolveClipCullDistanceSupport(true, true, false));
    }

    @Test
    void mobileGluesRequiresTheExtensionDespiteDesktopVersionString() {
        // Issue #214: MobileGlues reports a desktop GL version but compiles shaders through ESSL
        // translation, so the !isGLES shortcut must not apply to it.
        assertFalse(RenderSystem.resolveClipCullDistanceSupport(false, false, true),
            "MobileGlues without backend GL_EXT_clip_cull_distance must report no support");
        assertTrue(RenderSystem.resolveClipCullDistanceSupport(true, false, true),
            "MobileGlues advertises GL_EXT_clip_cull_distance when the real ES backend supports it");
    }
}
