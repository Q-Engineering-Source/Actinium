package com.dhj.actinium.mixins;

import com.dhj.actinium.render.particle.ParticleViewportCulling;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void resetCullingGate() {
        ParticleViewportCulling.setCullingDisabled(false);
    }

    @Test
    void keepsCullingMixinWhenParticleCullingIsPresentAndDisablesOnlyTheFrustumTest() {
        assertTrue(IrisMixinConfigPlugin.shouldApply(CULLING_MIXIN, PARTICLE_CULLING_MARKER::equals),
            "the mixin also submits particles to the batch, so it cannot be dropped");
        assertTrue(ParticleViewportCulling.isCullingDisabled());
    }

    @Test
    void keepsCullingMixinAndFrustumTestWhenParticleCullingIsAbsent() {
        assertTrue(IrisMixinConfigPlugin.shouldApply(CULLING_MIXIN, className -> false));
        assertFalse(ParticleViewportCulling.isCullingDisabled());
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
