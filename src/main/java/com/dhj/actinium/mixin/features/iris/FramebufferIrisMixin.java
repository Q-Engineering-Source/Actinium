package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.framebuffer.MinecraftFramebufferHelper;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMTexEnvGen;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;

@Mixin(Framebuffer.class)
public class FramebufferIrisMixin implements IRenderTargetExt {
    @Unique
    private int actinium$depthBufferVersion;

    @Unique
    private int actinium$colorBufferVersion;

    @Unique
    private int actinium$depthTextureId = -1;

    @Shadow
    public boolean useDepth;

    @Shadow
    public int framebufferTextureWidth;

    @Shadow
    public int framebufferTextureHeight;

    @Shadow
    public int framebufferWidth;

    @Shadow
    public int framebufferHeight;

    @Shadow
    public int framebufferObject;

    @Shadow
    public int framebufferTexture;

    @Shadow
    private boolean stencilEnabled;

    @Unique
    private void actinium$prepareFramebufferOutputState() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE3);
        GLStateManager.disableTexture();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
        GLStateManager.disableTexture();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLStateManager.disableTexture();
        GLSMTextures.glActiveTexture(OpenGlHelper.defaultTexUnit);
        GLStateManager.enableTexture();
        GLSMTexEnvGen.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GLStateManager.disableAlphaTest();
        GLSMCurrentAttribs.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GLSMBlend.glColorMask(true, true, true, true);
    }

    @Inject(method = "deleteFramebuffer()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/shader/Framebuffer;unbindFramebuffer()V", shift = At.Shift.AFTER))
    private void actinium$onDestroyBuffers(CallbackInfo ci) {
        this.actinium$depthBufferVersion++;
        this.actinium$colorBufferVersion++;
    }

    @Inject(method = "bindFramebuffer(Z)V", at = @At("RETURN"))
    private void actinium$restoreMinecraftFramebufferBuffers(boolean setViewport, CallbackInfo ci) {
        MinecraftFramebufferHelper.restoreMinecraftFramebufferBuffers();
    }

    @Inject(method = "unbindFramebuffer()V", at = @At("RETURN"))
    private void actinium$restoreDefaultFramebufferBuffers(CallbackInfo ci) {
        MinecraftFramebufferHelper.restoreDefaultFramebufferBuffers();
    }

    @Inject(method = "framebufferRenderExt(IIZ)V", at = @At("HEAD"))
    private void actinium$beginFramebufferOutputDiagnostics(int width, int height, boolean disableBlend, CallbackInfo ci) {
        if (IrisApiV0Impl.INSTANCE.isShaderPackInUse()) {
            this.actinium$prepareFramebufferOutputState();
        }
        IrisGlDebug.markStage("framebuffer-output:entry");
        IrisGlDebug.beginFramebufferSamplePhase("minecraft-output");
        IrisGlDebug.logFramebufferOutputState(
            "entry",
            this.framebufferTexture,
            this.framebufferWidth,
            this.framebufferHeight,
            this.framebufferTextureWidth,
            this.framebufferTextureHeight,
            width,
            height,
            disableBlend
        );
        IrisGlDebug.logCurrentFramebufferSamples("before-framebuffer-render-ext", 1);
    }

    @Inject(
        method = "framebufferRenderExt(IIZ)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebufferTexture()V")
    )
    private void actinium$beforeFramebufferTextureBind(int width, int height, boolean disableBlend, CallbackInfo ci) {
        if (IrisApiV0Impl.INSTANCE.isShaderPackInUse()) {
            this.actinium$prepareFramebufferOutputState();
        }
        IrisGlDebug.logFramebufferOutputState(
            "before-bind-texture",
            this.framebufferTexture,
            this.framebufferWidth,
            this.framebufferHeight,
            this.framebufferTextureWidth,
            this.framebufferTextureHeight,
            width,
            height,
            disableBlend
        );
    }

    @Inject(
        method = "framebufferRenderExt(IIZ)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebufferTexture()V", shift = At.Shift.AFTER)
    )
    private void actinium$afterFramebufferTextureBind(int width, int height, boolean disableBlend, CallbackInfo ci) {
        IrisGlDebug.logFramebufferOutputState(
            "after-bind-texture",
            this.framebufferTexture,
            this.framebufferWidth,
            this.framebufferHeight,
            this.framebufferTextureWidth,
            this.framebufferTextureHeight,
            width,
            height,
            disableBlend
        );
    }

    @Inject(
        method = "framebufferRenderExt(IIZ)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V")
    )
    private void actinium$beforeFramebufferOutputDraw(int width, int height, boolean disableBlend, CallbackInfo ci) {
        IrisGlDebug.logFramebufferOutputState(
            "before-draw",
            this.framebufferTexture,
            this.framebufferWidth,
            this.framebufferHeight,
            this.framebufferTextureWidth,
            this.framebufferTextureHeight,
            width,
            height,
            disableBlend
        );
    }

    @Inject(
        method = "framebufferRenderExt(IIZ)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V", shift = At.Shift.AFTER)
    )
    private void actinium$afterFramebufferOutputDraw(int width, int height, boolean disableBlend, CallbackInfo ci) {
        IrisGlDebug.logFramebufferOutputState(
            "after-draw",
            this.framebufferTexture,
            this.framebufferWidth,
            this.framebufferHeight,
            this.framebufferTextureWidth,
            this.framebufferTextureHeight,
            width,
            height,
            disableBlend
        );
    }

    @Inject(method = "framebufferRenderExt(IIZ)V", at = @At("RETURN"))
    private void actinium$endFramebufferOutputDiagnostics(int width, int height, boolean disableBlend, CallbackInfo ci) {
        if (IrisApiV0Impl.INSTANCE.isShaderPackInUse()) {
            GLSMTextures.glActiveTexture(OpenGlHelper.defaultTexUnit);
            GLSMBlend.glColorMask(true, true, true, true);
        }
        IrisGlDebug.logFramebufferOutputState(
            "return",
            this.framebufferTexture,
            this.framebufferWidth,
            this.framebufferHeight,
            this.framebufferTextureWidth,
            this.framebufferTextureHeight,
            width,
            height,
            disableBlend
        );
        IrisGlDebug.logCurrentFramebufferSamples("after-framebuffer-render-ext", 1);
        IrisGlDebug.endFramebufferSamplePhase();
        IrisGlDebug.markStage("framebuffer-output:return");
    }

    @Inject(method = "deleteFramebuffer()V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/shader/Framebuffer;depthBuffer:I", shift = At.Shift.BEFORE, ordinal = 0))
    private void actinium$deleteDepthTexture(CallbackInfo ci) {
        if (this.actinium$depthTextureId > -1) {
            GLSMTextures.glDeleteTextures(this.actinium$depthTextureId);
            this.actinium$depthTextureId = -1;
        }
    }

    @Redirect(method = "createFramebuffer(II)V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/shader/Framebuffer;useDepth:Z"))
    private boolean actinium$skipVanillaDepthRenderbuffer(Framebuffer framebuffer) {
        return false;
    }

    @Inject(
        method = "createFramebuffer(II)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/shader/Framebuffer;framebufferClear()V", shift = At.Shift.BEFORE, ordinal = 1)
    )
    private void actinium$createDepthTexture(int width, int height, CallbackInfo ci) {
        if (!this.useDepth) {
            return;
        }

        this.actinium$depthTextureId = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, this.actinium$depthTextureId);

        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, 0);

        if (this.stencilEnabled) {
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, width, height, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (IntBuffer) null);
            GLSMFramebuffer.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.actinium$depthTextureId, 0);
            GLSMFramebuffer.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, this.actinium$depthTextureId, 0);
        } else {
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (IntBuffer) null);
            GLSMFramebuffer.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.actinium$depthTextureId, 0);
        }

        this.actinium$depthBufferVersion++;
    }

    @Override
    public int iris$getDepthBufferVersion() {
        return this.actinium$depthBufferVersion;
    }

    @Override
    public int iris$getColorBufferVersion() {
        return this.actinium$colorBufferVersion;
    }

    @Override
    public int iris$getDepthTextureId() {
        return this.actinium$depthTextureId;
    }
}
