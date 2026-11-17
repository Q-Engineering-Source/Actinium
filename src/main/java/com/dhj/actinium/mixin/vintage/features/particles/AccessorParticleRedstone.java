package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleRedstone;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleRedstone, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleRedstone.class)
public interface AccessorParticleRedstone {

    @Accessor("reddustParticleScale")
    float actinium$reddustParticleScale();
}
