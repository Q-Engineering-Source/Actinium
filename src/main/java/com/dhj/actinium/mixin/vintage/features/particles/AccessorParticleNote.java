package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.ParticleNote;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the base size of ParticleNote, whose renderParticle scales it by age before drawing. */
@Mixin(ParticleNote.class)
public interface AccessorParticleNote {

    @Accessor("noteParticleScale")
    float actinium$noteParticleScale();
}
