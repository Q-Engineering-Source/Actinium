package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleFlame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleFlame, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleFlame.class)
public interface AccessorParticleFlame {

    @Accessor("flameScale")
    float actinium$flameScale();
}
