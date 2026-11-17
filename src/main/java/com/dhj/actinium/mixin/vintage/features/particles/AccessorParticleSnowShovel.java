package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleSnowShovel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleSnowShovel, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleSnowShovel.class)
public interface AccessorParticleSnowShovel {

    @Accessor("snowDigParticleScale")
    float actinium$snowDigParticleScale();
}
