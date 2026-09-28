package com.dhj.actinium.mixins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisMixinConfigPluginTest {
    private static final String CULLING_MIXIN =
        "com.dhj.actinium.mixin.features.iris.ParticleManagerCullingMixin";
    private static final String PARTICLE_CULLING_MARKER =
        "bl4ckscor3.mod.particleculling.mixin.MixinParticleManager";
    private static final String MEKANISM_GLOW_BLEND_MIXIN =
        "com.dhj.actinium.mixin.features.iris.MekanismGlowBlendMixin";
    private static final String MEKANISM_RENDERER_MARKER = "mekanism.client.render.MekanismRenderer";

    @Test
    void dropsCullingMixinWhenParticleCullingIsPresent() {
        assertFalse(IrisMixinConfigPlugin.shouldApply(CULLING_MIXIN, PARTICLE_CULLING_MARKER::equals));
    }

    @Test
    void keepsCullingMixinWhenParticleCullingIsAbsent() {
        assertTrue(IrisMixinConfigPlugin.shouldApply(CULLING_MIXIN, className -> false));
    }

    @Test
    void keepsOtherIrisMixinsRegardlessOfParticleCulling() {
        String otherMixin = "com.dhj.actinium.mixin.features.iris.ParticleManagerIrisMixin";

        assertTrue(IrisMixinConfigPlugin.shouldApply(otherMixin, PARTICLE_CULLING_MARKER::equals));
        assertTrue(IrisMixinConfigPlugin.shouldApply(otherMixin, className -> false));
    }

    @Test
    void appliesMekanismGlowBlendMixinOnlyWhenRendererClassIsPresent() {
        assertTrue(IrisMixinConfigPlugin.shouldApply(
            MEKANISM_GLOW_BLEND_MIXIN,
            MEKANISM_RENDERER_MARKER::equals
        ));
        assertFalse(IrisMixinConfigPlugin.shouldApply(MEKANISM_GLOW_BLEND_MIXIN, className -> false));
    }

}
