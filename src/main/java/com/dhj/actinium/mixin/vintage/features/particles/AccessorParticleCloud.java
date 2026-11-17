package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleCloud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleCloud, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleCloud.class)
public interface AccessorParticleCloud {

    @Accessor("oSize")
    float actinium$oSize();
}
