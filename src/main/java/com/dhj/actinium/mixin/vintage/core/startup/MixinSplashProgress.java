package com.dhj.actinium.mixin.vintage.core.startup;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import com.dhj.actinium.render.BufferBuilderStreamingDrawer;
import com.dhj.actinium.render.VanillaVertexBufferRenderer;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SuppressWarnings("deprecation")
@Mixin(targets = "net/minecraftforge/fml/client/SplashProgress")
public class MixinSplashProgress {
    @Unique
    private static final Logger celeritas$LOGGER = LogManager.getLogger("Celeritas");

    @Inject(method = "getMaxTextureSize", at = @At("HEAD"), cancellable = true)
    private static void celeritas$getMaxTextureSize(CallbackInfoReturnable<Integer> cir) {
        int maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (maxTextureSize <= 0) {
            celeritas$LOGGER.error(
                "OpenGL returned invalid GL_MAX_TEXTURE_SIZE during splash initialization: {}",
                maxTextureSize
            );
            throw new IllegalStateException("Invalid GL_MAX_TEXTURE_SIZE: " + maxTextureSize);
        }
        cir.setReturnValue(maxTextureSize);
    }

    @Inject(method = "start", at = @At("HEAD"))
    private static void celeritas$initSplashTessellator(CallbackInfo ci) {
        ImmediateModeRecorder.initSplashTessellator();
    }

    /**
     * Disables the FML splash on the SDL backend.
     *
     * <p>Forge's splash renders through a GL shared context, which does not exist once the SDL
     * backend owns the window. Cancelling {@code start()} at HEAD leaves {@code enabled} at its
     * false default, so {@code finish()}, {@code drawVanillaScreen()} and friends all take
     * Forge's own {@code !enabled} early returns. A HEAD inject is used instead of redirecting
     * the {@code getBool("enabled")} read because splash replacers like ModernSplash swap the
     * whole class body (their {@code CustomSplash.start()} has no such call), and a HEAD inject
     * has no instruction target to miss. The SDL backend presents its own startup screen.</p>
     */
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private static void celeritas$sdlDisableSplash(CallbackInfo ci) {
        if (SDLGPUGate.isEngaged()) {
            ci.cancel();
        }
    }

    @Inject(method = "finish", at = @At("RETURN"))
    private static void celeritas$finishSplash(CallbackInfo ci) {
        ImmediateModeRecorder.destroySplashTessellator();
        TessellatorStreamingDrawer.destroy();
        // Splash replacements (e.g. modernsplash's CustomSplash) can finish with the game on a
        // different GL context than the one startup used (issue #150). Container objects (VAOs)
        // from the startup context are invalid on the new one, while VBOs are shared and stay
        // valid, so rebuild every VAO born on another context. No-op when nothing migrated.
        if (GLStateManager.displayContextMigrated()) {
            GLStateManager.recreateDefaultVertexArray();
        }
        VanillaVertexBufferRenderer.recreateVertexArrays();
        BufferBuilderStreamingDrawer.recreateVertexArrays();
        GLStateManager.glBindVertexArray(0);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLStateManager.markSplashComplete();
    }
}
