package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.render.terrain.ActiniumWorldRenderer;
import com.dhj.actinium.render.particle.ParticleViewportCulling;
import com.gtnewhorizons.angelica.rendering.particles.ParticleInstancer;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Submits a particle to the instancer, and skips particles whose bounding box falls outside the last
 * rendered viewport.
 *
 * <p>An invocation can only carry one plain redirect, so the batch and the frustum test share the
 * handler: the batch decides first (it owns the particle completely when it answers), and the
 * frustum test decides whether the vanilla write still happens.</p>
 *
 * <p>ParticleCulling declares its own plain {@code @Redirect} on the same two
 * {@code Particle.renderParticle} call sites. Plain redirects cannot be chained, so the presence of
 * that mod only disables the frustum test inside this handler (see
 * {@link ParticleViewportCulling#setCullingDisabled}); dropping the whole class would drop the batch
 * with it.</p>
 */
@Mixin(ParticleManager.class)
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

    @Redirect(
        method = "renderParticles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V")
    )
    private void actinium$submitOrCullParticle(
        Particle particle,
        BufferBuilder buffer,
        Entity entityIn,
        float partialTicks,
        float rotationX,
        float rotationZ,
        float rotationYZ,
        float rotationXY,
        float rotationXZ
    ) {
        if (ParticleInstancer.renderParticle(particle, buffer, entityIn, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ)) {
            return;
        }
        ParticleViewportCulling.renderIfVisible(
            particle, this.actinium$cullingViewport, buffer, entityIn, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ
        );
    }

    /**
     * Lit particles have no {@code begin}/{@code draw} pair in 1.12.2, so they never join the batch and
     * keep their vanilla visibility, with the frustum test applied exactly as before.
     */
    @Redirect(
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
        float rotationXZ
    ) {
        ParticleViewportCulling.renderIfVisible(
            particle, this.actinium$cullingViewport, buffer, entityIn, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ
        );
    }

    @Unique
    private void actinium$setupCullingViewport() {
        ActiniumWorldRenderer renderer = ActiniumWorldRenderer.instanceNullable();
        this.actinium$cullingViewport = renderer != null ? renderer.getLastViewport() : null;
    }
}
