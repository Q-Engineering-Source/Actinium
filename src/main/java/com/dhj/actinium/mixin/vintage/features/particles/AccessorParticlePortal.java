package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticlePortal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticlePortal, whose renderParticle scales it by age before drawing. */
@Mixin(ParticlePortal.class)
public interface AccessorParticlePortal {

    @Accessor("portalParticleScale")
    float actinium$portalParticleScale();
}
