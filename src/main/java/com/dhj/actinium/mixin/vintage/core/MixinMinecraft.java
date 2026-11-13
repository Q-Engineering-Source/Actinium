package com.dhj.actinium.mixin.vintage.core;

import net.coderbot.iris.debug.flight.GlFlightRecording;
import net.coderbot.iris.debug.flight.GlFlightStreamingSource;
import com.dhj.actinium.gui.ActiniumWindowModeController;
import com.dhj.actinium.render.BufferBuilderStreamingDrawer;
import com.dhj.actinium.render.EndPortalCompositeRenderer;
import com.dhj.actinium.render.frame.FramePacer;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.startup.SdlFrameLifecycle;
import com.gtnewhorizons.angelica.glsm.GLSMTextureLifetime;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchej123.lwjgl.LWJGLServiceProvider;
import net.minecraft.client.Minecraft;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCulling;
import dhj.embeddedt.embeddium.impl.render.frame.RenderAheadManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Unique
    private final RenderAheadManager celeritas$renderAheadManager = new RenderAheadManager();

    @Inject(method = "runTick", at = @At("HEAD"))
    private void preRender(CallbackInfo ci) {
        ActiniumWindowModeController.synchronize((Minecraft) (Object) this);
    }

    @Inject(method = "toggleFullscreen", at = @At("HEAD"), cancellable = true)
    private void actinium$toggleFullscreenMode(CallbackInfo ci) {
        ActiniumWindowModeController.toggleFullscreen((Minecraft) (Object) this);
        ci.cancel();
    }

    @Inject(method = "getLimitFramerate", at = @At("HEAD"), cancellable = true)
    private void actinium$useLoadingScreenFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.world == null && minecraft.currentScreen != null) {
            cir.setReturnValue(ActiniumRuntime.options().performance.loadingScreenFramerateLimit);
        }
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void beginRenderFrame(CallbackInfo ci) {
        SdlFrameLifecycle.beginFrame();
        GlFlightRecording.beginFrame();
        EndPortalCompositeRenderer.beginFrame();
        final int limit = supportsCpuRenderAhead() ? ActiniumRuntime.options().advanced.cpuRenderAheadLimit : 0;
        if (limit > 0) {
            celeritas$renderAheadManager.startFrame(limit);
        }
    }

    @Inject(
            method = "runGameLoop",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;updateDisplay()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void endStreamingFrame(CallbackInfo ci) {
        GLSMTextureLifetime.onFrameEnd();
        ShaderManager.endFrame();
        if (supportsCpuRenderAhead() && ActiniumRuntime.options().advanced.cpuRenderAheadLimit > 0) {
            celeritas$renderAheadManager.endFrame();
        }
        GlFlightRecording.beginStreamingSync(GlFlightStreamingSource.TESSELLATOR);
        TessellatorStreamingDrawer.endFrame();
        GlFlightRecording.endStreamingSync(GlFlightStreamingSource.TESSELLATOR);
        GlFlightRecording.beginStreamingSync(GlFlightStreamingSource.BUFFER_BUILDER);
        BufferBuilderStreamingDrawer.endFrame();
        GlFlightRecording.endStreamingSync(GlFlightStreamingSource.BUFFER_BUILDER);
        FramePacer.beforePresent();
        GlFlightRecording.beginSwap();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void actinium$endBackendFrame(CallbackInfo ci) {
        SdlFrameLifecycle.endFrame();
    }

    @Inject(method = "updateDisplay", at = @At("RETURN"))
    private void actinium$beginBackendFrame(CallbackInfo ci) {
        SdlFrameLifecycle.beginFrame();
    }

    @Inject(
            method = "runGameLoop",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;updateDisplay()V",
                    shift = At.Shift.AFTER
            )
    )
    private void actinium$finishDisplaySwap(CallbackInfo ci) {
        GlFlightRecording.endSwap();
    }

    /**
     * Replaces the unconditional {@code Thread.yield()} that follows presentation. The pacer decides the next frame's
     * start time from the gate sample recorded around the swap, and calls the original yield only when it is not
     * pacing the frame itself.
     */
    @WrapOperation(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V", remap = false)
    )
    private void actinium$paceFrame(Operation<Void> original) {
        final Minecraft minecraft = (Minecraft) (Object) this;
        final int capHz = minecraft.isFramerateLimitBelowMax() ? minecraft.getLimitFramerate() : 0;
        FramePacer.endFrame(capHz, null);
        if (!FramePacer.pacedLastFrame()) {
            original.call();
        }
    }

    /**
     * Drops the vanilla frame limiter. lwjglx implements it as a one-millisecond sleep followed by a yield loop, which
     * stacks on top of the driver's vblank wait and adds jitter instead of bounding the frame rate.
     */
    @WrapOperation(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;sync(I)V", remap = false)
    )
    private void actinium$skipVanillaFpsLimit(int fps, Operation<Void> original) {
        // The pacer owns the cadence, so the vanilla limiter's sleep-and-yield loop is not called.
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void actinium$finishFlightRecorderFrame(CallbackInfo ci) {
        GlFlightRecording.endFrame();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("RETURN"))
    private void actinium$closeFlightRecorder(CallbackInfo ci) {
        GlFlightRecording.closeNormally();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("RETURN"))
    private void actinium$shutdownGpuCulling(CallbackInfo ci) {
        GpuCulling.shutdown();
    }

    @Unique
    private static boolean supportsCpuRenderAhead() {
        return LWJGLServiceProvider.LWJGL.isOpenGLVersionSupported(3, 2);
    }
}

