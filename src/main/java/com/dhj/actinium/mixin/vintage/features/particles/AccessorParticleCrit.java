package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleCrit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleCrit, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleCrit.class)
public interface AccessorParticleCrit {

    @Accessor("oSize")
    float actinium$oSize();
}
