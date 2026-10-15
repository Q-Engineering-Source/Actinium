package com.dhj.actinium.mixin.vintage.core;

import net.coderbot.iris.debug.flight.GlFlightRecording;
import net.coderbot.iris.debug.flight.GlFlightStreamingSource;
import com.dhj.actinium.gui.ActiniumWindowModeController;
import com.dhj.actinium.render.BufferBuilderStreamingDrawer;
import com.dhj.actinium.render.EndPortalCompositeRenderer;
import com.dhj.actinium.render.pacing.FpsReducer;
import com.dhj.actinium.render.pacing.FramePacer;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
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
    private static boolean actinium$hadWorld;

    /**
     * The render-ahead fence wait. It belongs after the swap, before the next frame's work starts,
     * because that is the first point at which the previous frame's commands are known to have been
     * submitted; the pacer calls it and charges the wait to the frame that is about to start.
     */
    @Unique
    private final Runnable celeritas$renderAheadWait = () -> {
        final int limit = supportsCpuRenderAhead() ? ActiniumRuntime.options().advanced.cpuRenderAheadLimit : 0;
        if (limit > 0) {
            celeritas$renderAheadManager.startFrame(limit);
        }
    };

    /**
     * Routes vanilla's startup vsync switch through the render backend.
     *
     * <p>The pacer decides whether the driver will block on the swap from the backend's effective
     * vsync mode, and only the backend knows what it actually asked the driver for. Letting vanilla
     * set the swap interval behind its back leaves the pacer believing a tearing interval is a
     * tear-free one, and it would then pace every frame onto the panel's cadence with vsync off.
     * Both backends' own entry points perform the call vanilla would have made, and on the SDL
     * backend lwjglx's call targets a window the backend does not own.</p>
     */
    @Redirect(
            method = "init",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setVSyncEnabled(Z)V", remap = false)
    )
    private void actinium$routeVSync(boolean sync) {
        GLStateManager.setVSyncEnabled(sync);
    }

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
    }

    /**
     * Refreshes the input state right before the world is rendered.
     *
     * <p>The swap at the end of the frame also drains the window's event queue, so the mouse delta
     * the camera is built from is the one the previous frame polled: everything the player moved
     * during this frame's own work is applied a frame late. Draining the queue again here — the last
     * point before the camera is computed — removes that frame of latency. It costs a message pump,
     * and the pump is idempotent, so the swap's own call still happens.</p>
     */
    @Inject(
            method = "runGameLoop",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V"
            )
    )
    private void actinium$freshenMouseInput(CallbackInfo ci) {
        GLStateManager.pumpDisplayMessages();
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
        FramePacer.beforePresent();
        GlFlightRecording.beginSwap();
        // Frame boundary: notify the active render backend that the frame's draws are complete.
        BackendManager.RENDER_BACKEND.onFrameEnd();
    }

    /**
     * Takes over the frame rate limiter. Vanilla reaches {@code Display.sync} at the very end of the
     * loop, after the frame has already been presented, which limits the average rate but hands the
     * panel every bit of jitter the frame's own cost introduces. The pacer instead sleeps before the
     * present; the yield is the last point in the loop at which the swap is known to be behind us,
     * so it is where the pacer reads the driver's gate and starts the next frame. The yield is kept
     * for unpaced frames so the loop still gives the scheduler a chance to run.
     */
    @WrapWithCondition(method = "runGameLoop", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V", remap = false))
    private boolean actinium$paceFrame() {
        final Minecraft minecraft = (Minecraft) (Object) this;
        final boolean hasWorld = minecraft.world != null;
        if (hasWorld != actinium$hadWorld) {
            actinium$hadWorld = hasWorld;
            FramePacer.invalidate();
        }

        FpsReducer.evaluateFrame();
        final int capHz = FpsReducer.effectiveCap(minecraft.gameSettings.limitFramerate,
                minecraft.world == null && minecraft.currentScreen != null);
        FramePacer.endFrame(capHz, celeritas$renderAheadWait);
        return !FramePacer.pacedLastFrame();
    }

    /**
     * Retires vanilla's frame limiter. {@code Display.sync} sleeps on an independent timer that
     * knows nothing about the pacer's phase, so leaving it in would add a second, uncoordinated
     * sleep to every frame.
     */
    @WrapWithCondition(method = "runGameLoop", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;sync(I)V", remap = false))
    private boolean actinium$noopVanillaFpsLimiter(int fps) {
        return false;
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
     * Owns the swap and the window pump.
     *
     * <p>On the SDL backend {@code Display.update()} swaps the GL context of the window lwjglx would
     * have created, which the SDL backend does not own. Presentation is driven by the frame hooks
     * instead ({@code onFrameEnd} before this method submits the final target, {@code onFrameBegin}
     * after it starts the next frame); only the event and input pump {@code Display.update()} also
     * performs is kept, through the backend's own pump. Everything else in {@code updateDisplay},
     * including the resize check, runs unchanged.</p>
     *
     * <p>On the GL backends the swap is where the pacer's timing comes from, so the pump is done
     * first and the swap is timed on its own: {@code Display.update(true)} would file the window
     * event pump and the input polling under the driver's vsync gate, which is exactly the block the
     * pacer measures and must not be confused with. The measured block is recorded as the frame's
     * gate sample, and the frame's first clear is marked as belonging to the panel rather than to the
     * frame, because that is where a driver that still owns the previous buffer will block.</p>
     */
    @Redirect(
            method = "updateDisplay",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", remap = false)
    )
    private static void actinium$updateDisplay() {
        final RenderBackend backend = BackendManager.RENDER_BACKEND;
        backend.pumpDisplayMessages();
        if (SDLGPUGate.isEngaged()) {
            return;
        }
        if (!backend.wantsDisplayUpdateGateTiming()) {
            Display.update(false);
            return;
        }
        final long start = System.nanoTime();
        Display.update(false);
        final long end = System.nanoTime();
        backend.recordGate(end - start, end);
        GLStateManager.markFirstClearPending();
    }

    /**
     * Skips the GL display teardown on the SDL backend.
     *
     * <p>{@code Display.destroy()} releases GLFW's window callbacks (none of which the SDL window
     * installs) and destroys a GLFW window that does not exist. The window belongs to the bridge and
     * the GPU device is released by the render backend's own shutdown, which already ran by the time
     * this is called.</p>
     *
     * <p>require = 0: Cleanroom 0.7.x (LWJGLY) removed the {@code Display.destroy()} call from
     * {@code shutdownMinecraftApplet}, so there is nothing left to redirect there.</p>
     */
    @Redirect(
            method = "shutdownMinecraftApplet",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;destroy()V", remap = false),
            require = 0
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
