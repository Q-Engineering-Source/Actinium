package com.dhj.actinium.mixin.mod.botania;

import com.dhj.actinium.compat.botania.BotaniaGlStateCompat;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vazkii.botania.client.core.handler.BoundTileRenderer;

/**
 * Restores GL_TEXTURE_2D enablement after Botania's bound-tile wireframe pass.
 *
 * <p>Same GLSM state-cache leak as {@code BlockHighlightRenderHandler}: drawing the
 * coordinate-bound outlines can leave the active texture unit disabled, so the
 * following held-item pass renders flat white.
 */
@Mixin(value = BoundTileRenderer.class, remap = false)
public abstract class MixinBoundTileRenderer {

    @Inject(method = "onWorldRenderLast", at = @At("RETURN"))
    private static void actinium$restoreTextureAfterBoundTiles(RenderWorldLastEvent event, CallbackInfo ci) {
        BotaniaGlStateCompat.restoreTextureEnablement();
    }
}
