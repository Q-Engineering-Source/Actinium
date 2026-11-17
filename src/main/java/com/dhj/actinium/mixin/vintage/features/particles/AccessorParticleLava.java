package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleLava;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleLava, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleLava.class)
public interface AccessorParticleLava {

    @Accessor("lavaParticleScale")
    float actinium$lavaParticleScale();
}
