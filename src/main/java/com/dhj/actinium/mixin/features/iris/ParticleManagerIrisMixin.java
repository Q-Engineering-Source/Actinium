package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.client.rendering.DeferredDrawBatcher;
import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import com.gtnewhorizons.angelica.rendering.particles.ParticleInstancer;
import net.coderbot.iris.Iris;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleManager.class)
public class ParticleManagerIrisMixin {
    @Unique
    private WorldRenderingPhase actinium$previousParticlePhase;

    @Inject(method = "renderParticles", at = @At("HEAD"))
    private void actinium$beginParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        this.actinium$beginParticlePhase();
        ParticleRunSplitter.beginParticles();
    }

    @Inject(method = "renderParticles", at = @At("RETURN"))
    private void actinium$endParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        DeferredDrawBatcher.exitAndFlush();
        ParticleRunSplitter.endParticles();
        this.actinium$endParticlePhase();
    }

    @Inject(method = "renderLitParticles", at = @At("HEAD"))
    private void actinium$beginLitParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        this.actinium$beginParticlePhase();
    }

    @Inject(method = "renderLitParticles", at = @At("RETURN"))
    private void actinium$endLitParticles(Entity entityIn, float partialTicks, CallbackInfo ci) {
        this.actinium$endParticlePhase();
    }

    @Inject(
        method = "renderParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BufferBuilder;begin(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V",
            shift = At.Shift.AFTER
        )
    )
    private void actinium$beginParticleRun(Entity entityIn, float partialTicks, CallbackInfo ci) {
        ParticleRunSplitter.beginRun();
        ParticleInstancer.beginLayer();
        DeferredDrawBatcher.enter();
    }

    @Inject(
        method = "renderParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"
        )
    )
    private void actinium$endParticleRun(Entity entityIn, float partialTicks, CallbackInfo ci) {
        // Instance draws belong before the run's own draw: the run carries only the vertices the
        // batch could not express, and both have to be emitted before the pipeline moves on.
        ParticleInstancer.endLayer();
        DeferredDrawBatcher.exitAndFlush();
    }

    /**
     * Matches GTNH Angelica's notfine particle fix: avoid the first depth-mask disable in the vanilla
     * particle loop and leave depth state under the render pipeline's control.
     */
    @Redirect(
        method = "renderParticles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;depthMask(Z)V", ordinal = 0)
    )
    private void actinium$skipFirstParticleDepthMask(boolean flag) {
    }

    @Unique
    private void actinium$beginParticlePhase() {
        if (!Iris.enabled || !IrisApiV0Impl.INSTANCE.isShaderPackInUse()) {
            this.actinium$previousParticlePhase = null;
            return;
        }

        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline == null) {
            this.actinium$previousParticlePhase = null;
            return;
        }

        this.actinium$previousParticlePhase = pipeline.getPhase();
        pipeline.setPhase(WorldRenderingPhase.PARTICLES);
    }

    @Unique
    private void actinium$endParticlePhase() {
        if (this.actinium$previousParticlePhase == null) {
            return;
        }

        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline != null) {
            pipeline.setPhase(this.actinium$previousParticlePhase);
        }

        this.actinium$previousParticlePhase = null;
    }
}

