package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleSmokeNormal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleSmokeNormal, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleSmokeNormal.class)
public interface AccessorParticleSmokeNormal {

    @Accessor("smokeParticleScale")
    float actinium$smokeParticleScale();
}
