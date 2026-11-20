package com.dhj.actinium.mixin.mod.mekanism;

import com.dhj.actinium.mixinterface.features.iris.IndependentParticleDraw;
import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps laser geometry out of the particle capture buffer. */
@Mixin(targets = "mekanism.client.entity.ParticleLaser", remap = false)
public abstract class MixinParticleLaser implements IndependentParticleDraw {
    // Some builds bracket the beam with an outer draw and restart. Match both MCP and SRG names
    // because the members are called from a mod target rather than declared by the mixin target.
    @WrapWithCondition(
        method = { "renderParticle", "func_180434_a" },
        at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V", remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;func_78381_a()V", remap = false)
        },
        require = 0,
        remap = false
    )
    private boolean actinium$drawOuterRun(Tessellator tessellator) {
        return !ParticleRunSplitter.ownsIndependentBoundary(this);
    }

    @WrapWithCondition(
        method = { "renderParticle", "func_180434_a" },
        at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/BufferBuilder;begin(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V", remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/BufferBuilder;func_181668_a(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V", remap = false)
        },
        require = 0,
        remap = false
    )
    private boolean actinium$restartOuterRun(BufferBuilder buffer, int mode, VertexFormat format) {
        return !ParticleRunSplitter.ownsIndependentBoundary(this);
    }
}
