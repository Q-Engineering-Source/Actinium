package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleFirework;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the twinkle flag that decides whether a firework spark draws on a given frame. */
@Mixin(ParticleFirework.Spark.class)
public interface AccessorParticleFireworkSpark {

    @Accessor("twinkle")
    boolean actinium$twinkle();
}
