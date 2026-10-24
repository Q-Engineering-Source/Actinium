package com.dhj.actinium.mixin.mod.botania;

import com.dhj.actinium.compat.botania.BotaniaGlStateCompat;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vazkii.botania.client.core.handler.BlockHighlightRenderHandler;

/**
 * Restores GL_TEXTURE_2D enablement after Botania's block-highlight overlay pass.
 *
 * <p>Botania's {@code onWorldRenderLast} disables texture sampling for wireframe overlays;
 * under the GLSM state cache its {@code glPushAttrib}/{@code disableTexture} pairing can
 * leave the active texture unit disabled, so the following held-item pass renders flat white.
 */
@Mixin(value = BlockHighlightRenderHandler.class, remap = false)
public abstract class MixinBlockHighlightRenderHandler {

    @Inject(method = "onWorldRenderLast", at = @At("RETURN"))
    private static void actinium$restoreTextureAfterHighlight(RenderWorldLastEvent event, CallbackInfo ci) {
        BotaniaGlStateCompat.restoreTextureEnablement();
    }
}
