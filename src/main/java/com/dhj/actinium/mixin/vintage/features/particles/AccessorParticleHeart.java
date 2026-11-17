package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleHeart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleHeart, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleHeart.class)
public interface AccessorParticleHeart {

    @Accessor("particleScaleOverTime")
    float actinium$particleScaleOverTime();
}
