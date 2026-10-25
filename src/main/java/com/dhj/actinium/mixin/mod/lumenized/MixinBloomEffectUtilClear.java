package com.dhj.actinium.mixin.mod.lumenized;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import gregtech.client.utils.BloomEffectUtil;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lumenized creates its bloom FBO without clearing it, so the first frames show whatever GPU
 * memory the texture happened to reuse (often the previous main-framebuffer frame) as glow
 * that ignores depth (visible through walls). Clear right after the bloom FBO is bound so
 * only content actually rendered in this pass contributes to the bloom.
 */
@Mixin(value = BloomEffectUtil.class, remap = false)
public abstract class MixinBloomEffectUtilClear {
    private static int actinium$mainDepthBuffer;
    private static int actinium$mainDepthType;

    // GregTech CEu 2.8 splits the pass into the renderBloomBlockLayer wrapper and
    // renderBloomInternal (which holds the framebuffer calls); Lumenized keeps everything
    // inline in renderBloomBlockLayer. Target both and tolerate whichever is empty.
    @Inject(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebuffer(Z)V",
            ordinal = 0,
            shift = At.Shift.AFTER,
            remap = true
        ),
        remap = false,
        require = 0
    )
    private static void actinium$captureMainDepthBuffer(
        RenderGlobal renderGlobal,
        BlockRenderLayer layer,
        double partialTicks,
        int pass,
        Entity entity,
        CallbackInfoReturnable<Integer> cir
    ) {
        // Main framebuffer is bound here; remember its depth attachment so the bloom FBO
        // can share it (the bloom FBO is created without a depth attachment).
        actinium$mainDepthType = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        );
        actinium$mainDepthBuffer = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        );
    }

    @Inject(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebuffer(Z)V",
            ordinal = 1,
            shift = At.Shift.AFTER,
            remap = true
        ),
        remap = false,
        require = 0
    )
    private static void actinium$clearBloomFbo(
        RenderGlobal renderGlobal,
        BlockRenderLayer layer,
        double partialTicks,
        int pass,
        Entity entity,
        CallbackInfoReturnable<Integer> cir
    ) {
        // The bloom FBO shares the main framebuffer's depth buffer, so only the color
        // attachment may be cleared — clearing depth would wipe the world depth and let
        // the glow pass through everything.
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        // The bloom FBO is created without a depth attachment; share the main framebuffer's
        // depth buffer so bloom content is occluded by world geometry, and never write depth
        // (writing would corrupt the main depth used by the composite).
        if (actinium$mainDepthBuffer != 0) {
            if (actinium$mainDepthType == GL30.GL_RENDERBUFFER) {
                GL30.glFramebufferRenderbuffer(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER,
                    actinium$mainDepthBuffer
                );
            } else if (actinium$mainDepthType == GL30.GL_TEXTURE) {
                GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_TEXTURE_2D,
                    actinium$mainDepthBuffer,
                    0
                );
            }
        }
        GLStateManager.enableDepthTest();
        GLStateManager.glDepthMask(false);
    }
}
