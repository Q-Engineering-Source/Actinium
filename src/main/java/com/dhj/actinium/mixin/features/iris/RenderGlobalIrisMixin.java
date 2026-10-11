package com.dhj.actinium.mixin.features.iris;

import net.coderbot.iris.Iris;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.SkyRenderDistance;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import com.gtnewhorizons.angelica.glsm.CompatUniformManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.RayTraceResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderGlobal.class)
public class RenderGlobalIrisMixin {
    @Inject(method = "renderSky(FI)V", at = @At("HEAD"))
    private void actinium$beginSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!Iris.enabled) {
            return;
        }

        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline != null) {
            pipeline.setPhase(WorldRenderingPhase.CUSTOM_SKY);
        }
    }

    @Inject(method = "renderSky(FI)V", at = @At("RETURN"))
    private void actinium$endSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!Iris.enabled) {
            return;
        }

        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline != null) {
            pipeline.setPhase(WorldRenderingPhase.NONE);
        }
    }

    @Redirect(
        method = "renderClouds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/settings/GameSettings;shouldRenderClouds()I")
    )
    private int actinium$forceCloudsBelowMinimumDistance(GameSettings settings) {
        return settings.renderDistanceChunks < SkyRenderDistance.MINIMUM_RENDER_DISTANCE_CHUNKS
            && settings.clouds == 0 ? 2 : settings.clouds;
    }

    @Inject(
        method = "renderSky(FI)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;disableTexture2D()V",
            ordinal = 0
        )
    )
    private void actinium$beginVanillaSky(float partialTicks, int pass, CallbackInfo ci) {
        actinium$setSkyPhase(WorldRenderingPhase.SKY);
    }

    @Inject(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldProvider;calcSunriseSunsetColors(FF)[F")
    )
    private void actinium$beginSunset(float partialTicks, int pass, CallbackInfo ci) {
        actinium$setSkyPhase(WorldRenderingPhase.SUNSET);
    }

    @Inject(
        method = "renderSky(FI)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;enableTexture2D()V",
            ordinal = 0
        )
    )
    private void actinium$endSunset(float partialTicks, int pass, CallbackInfo ci) {
        actinium$setSkyPhase(WorldRenderingPhase.SKY);
    }

    @Inject(
        method = "renderSky(FI)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getCelestialAngle(F)F",
            ordinal = 1
        )
    )
    private void actinium$applySunPathRotation(float partialTicks, int pass, CallbackInfo ci) {
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        if (pipeline != null) {
            GlStateManager.rotate(pipeline.getSunPathRotation(), 0.0F, 0.0F, 1.0F);
        }
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V",
            ordinal = 0
        )
    )
    private void actinium$bindSun(TextureManager textureManager, ResourceLocation location) {
        actinium$setSkyPhase(WorldRenderingPhase.SUN);
        textureManager.bindTexture(location);
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V",
            ordinal = 1
        )
    )
    private void actinium$bindMoon(TextureManager textureManager, ResourceLocation location) {
        actinium$setSkyPhase(WorldRenderingPhase.MOON);
        textureManager.bindTexture(location);
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V", ordinal = 1)
    )
    private void actinium$drawSun(Tessellator tessellator) {
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        boolean directive = pipeline == null || pipeline.shouldRenderSun();
        if (directive) {
            actinium$refreshCurrentProgramCompatUniforms(pipeline);
            tessellator.draw();
        } else {
            actinium$discard(tessellator.getBuffer());
        }
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V", ordinal = 2)
    )
    private void actinium$drawMoon(Tessellator tessellator) {
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        boolean directive = pipeline == null || pipeline.shouldRenderMoon();
        if (directive) {
            actinium$refreshCurrentProgramCompatUniforms(pipeline);
            tessellator.draw();
        } else {
            actinium$discard(tessellator.getBuffer());
        }
        actinium$setSkyPhase(WorldRenderingPhase.STARS);
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/vertex/VertexBuffer;drawArrays(I)V", ordinal = 0)
    )
    private void actinium$drawSkyDiscVbo(VertexBuffer vertexBuffer, int mode) {
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        boolean directive = pipeline == null || pipeline.shouldRenderSkyDisc();
        if (directive) {
            vertexBuffer.drawArrays(mode);
        }
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;callList(I)V", ordinal = 0)
    )
    private void actinium$drawSkyDiscList(int list) {
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        boolean directive = pipeline == null || pipeline.shouldRenderSkyDisc();
        if (directive) {
            GlStateManager.callList(list);
        }
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/vertex/VertexBuffer;drawArrays(I)V", ordinal = 1)
    )
    private void actinium$drawStarsVbo(VertexBuffer vertexBuffer, int mode) {
        actinium$setSkyPhase(WorldRenderingPhase.STARS);
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        boolean directive = pipeline == null || pipeline.shouldRenderStars();
        if (directive) {
            vertexBuffer.drawArrays(mode);
        }
    }

    @Redirect(
        method = "renderSky(FI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;callList(I)V", ordinal = 1)
    )
    private void actinium$drawStarsList(int list) {
        actinium$setSkyPhase(WorldRenderingPhase.STARS);
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        boolean directive = pipeline == null || pipeline.shouldRenderStars();
        if (directive) {
            GlStateManager.callList(list);
        }
    }

    private static WorldRenderingPipeline actinium$getPipeline() {
        return Iris.enabled ? Iris.getPipelineManager().getPipelineNullable() : null;
    }

    private static void actinium$setSkyPhase(WorldRenderingPhase phase) {
        WorldRenderingPipeline pipeline = actinium$getPipeline();
        if (pipeline != null) {
            pipeline.setPhase(phase);
        }
    }

    private static void actinium$refreshCurrentProgramCompatUniforms(WorldRenderingPipeline pipeline) {
        if (pipeline instanceof DeferredWorldRenderingPipeline deferredPipeline) {
            int program = deferredPipeline.getActivePassProgramId();
            if (program > 0) {
                CompatUniformManager.onUseProgram(program);
            }
        }
    }

    private static void actinium$discard(BufferBuilder bufferBuilder) {
        try {
            bufferBuilder.finishDrawing();
        } catch (IllegalStateException ignored) {
        }
        bufferBuilder.reset();
    }
}
