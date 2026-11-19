package com.dhj.actinium.mixin.mod.dsurround;

import com.dhj.actinium.mixinterface.features.iris.IndependentParticleDraw;
import com.dhj.actinium.mixinterface.features.iris.ParticleCullingBypass;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps the collection's own draw and its child geometry outside the particle optimizations. */
@Mixin(targets = "org.orecruncher.dsurround.client.fx.particle.mote.ParticleCollection", remap = false)
public abstract class MixinParticleCollection implements ParticleCullingBypass, IndependentParticleDraw {
}
