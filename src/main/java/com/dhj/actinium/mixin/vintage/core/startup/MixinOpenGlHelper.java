package com.dhj.actinium.mixin.vintage.core.startup;

import com.dhj.actinium.debug.ActiniumStartupDebugConfig;
import net.coderbot.iris.debug.flight.GlFlightRecording;
import net.coderbot.iris.debug.flight.GlFlightGpuCommandRecorder;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingUploader;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import com.dhj.actinium.config.ActiniumRuntimeOptions;
import net.coderbot.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.render.BufferBuilderStreamingDrawer;
import com.dhj.actinium.render.EndPortalCompositeRenderer;

@Mixin(value = OpenGlHelper.class, priority = 100)
public class MixinOpenGlHelper {
    // GLContext.getCapabilities() call sites (including the one in initializeTextures) are rewritten
    // to GLStateManager.currentContextCapabilities() by the GLSM ASM redirector, so no @Redirect is
    // needed here — and keeping one would fail injection once the ASM pass removes its target.

    @Inject(method = "initializeTextures", at = @At("RETURN"))
    private static void celeritas$initializeGLStateManager(CallbackInfo ci) {
        final Minecraft mc = Minecraft.getMinecraft();

        GLStateManager.setDrawableGL(Display.getDrawable());
        GLStateManager.initialize(GLSMInitConfig.builder()
            .lwjglDebug(ActiniumStartupDebugConfig.enableLwjglDebug())
            .displaySize(mc.displayWidth, mc.displayHeight)
            .framebufferSupported(OpenGlHelper.framebufferSupported)
            .fboEnabled(mc.gameSettings.fboEnable)
            .streamingUploadStrategy(celeritas$streamingUploadStrategy())
            .gpuCommandRecorder(GlFlightRecording.isEnabled() ? GlFlightGpuCommandRecorder.INSTANCE : null)
            .directDrawer(TessellatorStreamingDrawer::drawDirect)
            .streamingDrawerDestroy(() -> {
                TessellatorStreamingDrawer.destroy();
                BufferBuilderStreamingDrawer.destroy();
                EndPortalCompositeRenderer.destroy();
            })
            .build());

        GLSMHooks.LIGHTMAP_COORDS.addListener(event -> {
            OpenGlHelper.lastBrightnessX = event.x;
            OpenGlHelper.lastBrightnessY = event.y;
        });

        if (Iris.enabled && Thread.currentThread() == GLStateManager.getMainThread()) {
            Iris.onRenderSystemInit();
        }
    }

    @Unique
    private static StreamingUploader.UploadStrategy celeritas$streamingUploadStrategy() {
        if (!ActiniumRuntimeOptions.allowDirectMemoryAccess()) {
            return StreamingUploader.UploadStrategy.BUFFER_DATA;
        }

        return ActiniumRuntime.options().advanced.streamingUploadStrategy.glsmStrategy();
    }
}

