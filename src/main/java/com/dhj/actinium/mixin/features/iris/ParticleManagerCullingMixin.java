package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.render.particle.ParticleViewportCulling;
import com.dhj.actinium.render.terrain.ActiniumWorldRenderer;
import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import com.gtnewhorizons.angelica.rendering.particles.ParticleInstancer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps both particle call sites before ParticleCulling's default-priority redirects rewrite them. */
@Mixin(value = ParticleManager.class, priority = 900)
public class ParticleManagerCullingMixin {
    @Unique
    private Viewport actinium$cullingViewport;

    @Inject(method = "renderParticles", at = @At("HEAD"))
    private void actinium$setupCullingViewportForParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        this.actinium$setupCullingViewport();
    }

    @Inject(method = "renderLitParticles", at = @At("HEAD"))
    private void actinium$setupCullingViewportForLitParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        this.actinium$setupCullingViewport();
    }

    @WrapOperation(
        method = "renderParticles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V")
    )
    private void actinium$cullParticle(
        Particle particle,
        BufferBuilder buffer,
        Entity entityIn,
        float partialTicks,
        float rotationX,
        float rotationZ,
        float rotationYZ,
        float rotationXY,
        float rotationXZ,
        Operation<Void> original
    ) {
        this.actinium$submitParticle(particle, buffer, entityIn, partialTicks, rotationX, rotationZ, rotationYZ,
            rotationXY, rotationXZ, original);
    }

    @WrapOperation(
        method = "renderLitParticles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V")
    )
    private void actinium$cullLitParticle(
        Particle particle,
        BufferBuilder buffer,
        Entity entityIn,
        float partialTicks,
        float rotationX,
        float rotationZ,
        float rotationYZ,
        float rotationXY,
        float rotationXZ,
        Operation<Void> original
    ) {
        this.actinium$submitParticle(particle, buffer, entityIn, partialTicks, rotationX, rotationZ, rotationYZ,
            rotationXY, rotationXZ, original);
    }

    @Unique
    private void actinium$submitParticle(Particle particle, BufferBuilder buffer, Entity entityIn, float partialTicks,
                                        float rotationX, float rotationZ, float rotationYZ, float rotationXY,
                                        float rotationXZ, Operation<Void> original) {
        if (!ParticleViewportCulling.isVisible(particle, this.actinium$cullingViewport)) {
            return;
        }
        if (ParticleRunSplitter.requiresIndependentDraw(particle)) {
            ParticleRunSplitter.renderIndependent(particle, () -> original.call(particle, buffer, entityIn, partialTicks,
                rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ));
            return;
        }
        ParticleRunSplitter.splitIfNeeded(particle, buffer);
        if (ParticleInstancer.renderParticle(particle, buffer, entityIn, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ)) {
            return;
        }
        original.call(particle, buffer, entityIn, partialTicks, rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ);
    }

    @Unique
    private void actinium$setupCullingViewport() {
        ActiniumWorldRenderer renderer = ActiniumWorldRenderer.instanceNullable();
        this.actinium$cullingViewport = renderer != null ? renderer.getLastViewport() : null;
    }
}
