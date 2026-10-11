package com.dhj.actinium.mixin.vintage.core;

import net.coderbot.iris.debug.flight.GlFlightRecording;
import net.coderbot.iris.debug.flight.GlFlightStreamingSource;
import com.dhj.actinium.gui.ActiniumWindowModeController;
import com.dhj.actinium.render.BufferBuilderStreamingDrawer;
import com.dhj.actinium.render.EndPortalCompositeRenderer;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.mitchej123.lwjgl.LWJGLServiceProvider;
import net.minecraft.client.Minecraft;
import dhj.embeddedt.embeddium.impl.render.frame.RenderAheadManager;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Unique
    private final RenderAheadManager celeritas$renderAheadManager = new RenderAheadManager();

    @Unique
    private static boolean actinium$gameLoopStarted;

    @Unique
    private static boolean actinium$vsyncPushed;

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
        GlFlightRecording.beginFrame();
        EndPortalCompositeRenderer.beginFrame();
        // Bootstrap the first frame: notify the active render backend that a frame group starts,
        // mirroring Angelica's MixinMinecraft_FrameHook.
        if (!actinium$gameLoopStarted) {
            actinium$gameLoopStarted = true;
            BackendManager.RENDER_BACKEND.onFrameBegin();
        }
        // The SDL backend presents through its own swapchain, so lwjglx's Display.setVSyncEnabled
        // (a glfwSwapInterval call on a window the backend does not own) cannot bound the frame rate.
        // Push the user's preference into the backend once; without it the client thread free-runs.
        final Minecraft minecraft = (Minecraft) (Object) this;
        if (SDLGPUGate.isEngaged() && !actinium$vsyncPushed && minecraft.gameSettings != null) {
            actinium$vsyncPushed = true;
            BackendManager.RENDER_BACKEND.setVSyncEnabled(minecraft.gameSettings.enableVsync);
        }
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
        if (supportsCpuRenderAhead() && ActiniumRuntime.options().advanced.cpuRenderAheadLimit > 0) {
            celeritas$renderAheadManager.endFrame();
        }
        GlFlightRecording.beginStreamingSync(GlFlightStreamingSource.TESSELLATOR);
        TessellatorStreamingDrawer.endFrame();
        GlFlightRecording.endStreamingSync(GlFlightStreamingSource.TESSELLATOR);
        GlFlightRecording.beginStreamingSync(GlFlightStreamingSource.BUFFER_BUILDER);
        BufferBuilderStreamingDrawer.endFrame();
        GlFlightRecording.endStreamingSync(GlFlightStreamingSource.BUFFER_BUILDER);
        GlFlightRecording.beginSwap();
        // Frame boundary: notify the active render backend that the frame's draws are complete.
        BackendManager.RENDER_BACKEND.onFrameEnd();
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
        // Frame boundary: the swap is done; a new frame's draws may begin.
        if (actinium$gameLoopStarted) {
            BackendManager.RENDER_BACKEND.onFrameBegin();
        }
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void actinium$finishFlightRecorderFrame(CallbackInfo ci) {
        GlFlightRecording.endFrame();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("RETURN"))
    private void actinium$closeFlightRecorder(CallbackInfo ci) {
        GlFlightRecording.closeNormally();
    }

    /**
     * Replaces the GL swap on the SDL backend.
     *
     * <p>{@code Display.update()} swaps the GL context of the window lwjglx would have created, which
     * the SDL backend does not own. Presentation is driven by the frame hooks instead
     * ({@code onFrameEnd} before this method submits the final target, {@code onFrameBegin} after it
     * starts the next frame); only the event and input pump {@code Display.update()} also performs is
     * kept, through the backend's own pump. Everything else in {@code updateDisplay}, including the
     * resize check, runs unchanged.</p>
     */
    @Redirect(
            method = "updateDisplay",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", remap = false)
    )
    private static void actinium$sdlUpdateDisplay() {
        if (!SDLGPUGate.isEngaged()) {
            Display.update();
            return;
        }
        BackendManager.RENDER_BACKEND.pumpDisplayMessages();
    }

    /**
     * Skips the GL display teardown on the SDL backend.
     *
     * <p>{@code Display.destroy()} releases GLFW's window callbacks (none of which the SDL window
     * installs) and destroys a GLFW window that does not exist. The window belongs to the bridge and
     * the GPU device is released by the render backend's own shutdown, which already ran by the time
     * this is called.</p>
     */
    @Redirect(
            method = "shutdownMinecraftApplet",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;destroy()V", remap = false)
    )
    private static void actinium$sdlDisplayDestroy() {
        if (SDLGPUGate.isEngaged()) {
            return;
        }
        Display.destroy();
    }

    @Unique
    private static boolean supportsCpuRenderAhead() {
        return LWJGLServiceProvider.LWJGL.isOpenGLVersionSupported(3, 2);
    }
}
