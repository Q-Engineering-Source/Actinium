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

/**
 * Decides, per particle, whether it is visible, whether the run has to change its declared
 * translucency, and whether the batch takes the particle instead of the vanilla write.
 *
 * <p>The two call sites are wrapped rather than redirected: a plain redirect occupies the
 * invocation, and ParticleCulling puts its own redirect on the same two invocations, which the
 * injector cannot chain with a second one at the same priority. A wrapped invocation leaves one
 * {@code Operation} that still performs the original call, so another redirect can sit beside it.
 * Mixin applies the lowest priority value first, so this class sits at 900, below the default 1000,
 * and discovers its targets before the default priority redirect rewrites them.</p>
 *
 * <p>The local frustum test always runs, for the batched and the fallback path alike: an invisible
 * particle must neither be batched nor written, and a particle the batch took never reaches the
 * {@code Operation}, so the test cannot be left to the caller. ParticleCulling, when present, adds
 * its own test on top; a second bounding-box test per particle is cheap.</p>
 */
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
    private void actinium$submitParticle(
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
        if (!ParticleViewportCulling.isVisible(particle, this.actinium$cullingViewport)) {
            return;
        }
        ParticleRunSplitter.splitIfNeeded(particle, buffer);
        if (ParticleInstancer.renderParticle(particle, buffer, entityIn, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ)) {
            return;
        }
        original.call(particle, buffer, entityIn, partialTicks, rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ);
    }

    /**
     * Lit particles have no {@code begin}/{@code draw} pair in 1.12.2, so they never join the batch and
     * keep their vanilla visibility with the frustum test applied exactly as before.
     */
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
        if (!ParticleViewportCulling.isVisible(particle, this.actinium$cullingViewport)) {
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
