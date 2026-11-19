package com.dhj.actinium.mixins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisMixinConfigPluginTest {
    private static final String MEKANISM_GLOW_BLEND_MIXIN =
        "com.dhj.actinium.mixin.features.iris.MekanismGlowBlendMixin";
    private static final String MEKANISM_RENDERER_MARKER = "mekanism.client.render.MekanismRenderer";

    @Test
    void appliesMekanismGlowBlendMixinOnlyWhenRendererClassIsPresent() {
        assertTrue(IrisMixinConfigPlugin.shouldApply(
            MEKANISM_GLOW_BLEND_MIXIN,
            MEKANISM_RENDERER_MARKER::equals
        ));
        assertFalse(IrisMixinConfigPlugin.shouldApply(MEKANISM_GLOW_BLEND_MIXIN, className -> false));
    }

}
