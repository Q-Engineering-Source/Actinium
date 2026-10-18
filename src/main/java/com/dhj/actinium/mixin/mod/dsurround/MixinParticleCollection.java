package com.dhj.actinium.mixin.mod.dsurround;

import com.dhj.actinium.mixinterface.features.iris.ParticleCullingBypass;
import org.spongepowered.asm.mixin.Mixin;

/** Marks Dynamic Surroundings' particle collection so its child geometry is not culled at the origin. */
@Mixin(targets = "org.orecruncher.dsurround.client.fx.particle.mote.ParticleCollection", remap = false)
public abstract class MixinParticleCollection implements ParticleCullingBypass {
}
