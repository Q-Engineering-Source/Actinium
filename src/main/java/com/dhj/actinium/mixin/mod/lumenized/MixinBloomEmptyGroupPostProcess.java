package com.dhj.actinium.mixin.mod.lumenized;

import codechicken.lib.render.shader.ShaderObject;
import codechicken.lib.render.shader.ShaderProgram;
import com.dhj.actinium.compat.lumenized.BloomSubmissionState;
import gregtech.client.shader.Shaders;
import gregtech.client.shader.postprocessing.BloomEffect;
import gregtech.client.utils.BloomEffectUtil;
import gregtech.client.utils.EffectRenderContext;
import gregtech.client.utils.IBloomEffect;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Consumer;

/**
 * Skips the per-ticket fullscreen work of the shared Lumenized/GTCEu bloom implementation
 * when every ticket in a group is inactive.
 *
 * <p>GregTech CEu 2.8 splits the pass into the renderBloomBlockLayer wrapper and
 * renderBloomInternal (which holds the framebuffer calls); Lumenized keeps everything
 * inline in renderBloomBlockLayer. Like MixinBloomEffectUtilClear, the pass redirects
 * target both and tolerate whichever is empty (require=0) — a miss only forfeits the
 * skip, never the rendered output. The draw-based tracking keeps require=1: draw has the
 * same shape in both mods, so a mismatch there means the class changed underneath us and
 * must fail loudly rather than skip real bloom (an applied tracker without an applied
 * marker would read every group as empty).
 */
@Pseudo
@Mixin(value = BloomEffectUtil.class, remap = false)
public abstract class MixinBloomEmptyGroupPostProcess {
    @Redirect(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(value = "INVOKE", target = "Lgregtech/client/utils/BloomEffectUtil;draw(Lnet/minecraft/client/renderer/BufferBuilder;Lgregtech/client/utils/EffectRenderContext;Ljava/util/List;)V", remap = false),
        remap = false,
        require = 0
    )
    private static void actinium$trackTicketGroup(
        final BufferBuilder buffer, final EffectRenderContext context, final List<?> tickets
    ) {
        BloomSubmissionState.beginGroup();
        try {
            BloomEffectUtilInvoker.actinium$invokeDraw(buffer, context, tickets);
        } finally {
            BloomSubmissionState.endGroup();
        }
    }

    @Redirect(
        method = "draw(Lnet/minecraft/client/renderer/BufferBuilder;Lgregtech/client/utils/EffectRenderContext;Ljava/util/List;)V",
        at = @At(value = "INVOKE", target = "Lgregtech/client/utils/IBloomEffect;renderBloomEffect(Lnet/minecraft/client/renderer/BufferBuilder;Lgregtech/client/utils/EffectRenderContext;)V", remap = false),
        remap = false,
        require = 1
    )
    private static void actinium$markRenderableTicket(
        final IBloomEffect effect,
        final BufferBuilder buffer,
        final EffectRenderContext context
    ) {
        BloomSubmissionState.markRenderable();
        effect.renderBloomEffect(buffer, context);
    }

    @Redirect(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(value = "INVOKE", target = "Lgregtech/client/shader/postprocessing/BloomEffect;renderLOG(Lnet/minecraft/client/shader/Framebuffer;Lnet/minecraft/client/shader/Framebuffer;)V", ordinal = 1, remap = false),
        remap = false,
        require = 0
    )
    private static void actinium$skipEmptyLogGroup(final Framebuffer source, final Framebuffer destination) {
        if (BloomSubmissionState.lastGroupHasContent()) {
            BloomEffect.renderLOG(source, destination);
        }
    }

    @Redirect(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(value = "INVOKE", target = "Lgregtech/client/shader/postprocessing/BloomEffect;renderUnity(Lnet/minecraft/client/shader/Framebuffer;Lnet/minecraft/client/shader/Framebuffer;)V", ordinal = 1, remap = false),
        remap = false,
        require = 0
    )
    private static void actinium$skipEmptyUnityGroup(final Framebuffer source, final Framebuffer destination) {
        if (BloomSubmissionState.lastGroupHasContent()) {
            BloomEffect.renderUnity(source, destination);
        }
    }

    @Redirect(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(value = "INVOKE", target = "Lgregtech/client/shader/postprocessing/BloomEffect;renderUnreal(Lnet/minecraft/client/shader/Framebuffer;Lnet/minecraft/client/shader/Framebuffer;)V", ordinal = 1, remap = false),
        remap = false,
        require = 0
    )
    private static void actinium$skipEmptyUnrealGroup(final Framebuffer source, final Framebuffer destination) {
        if (BloomSubmissionState.lastGroupHasContent()) {
            BloomEffect.renderUnreal(source, destination);
        }
    }

    @Redirect(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(value = "INVOKE", target = "Lgregtech/client/shader/Shaders;renderFullImageInFBO(Lnet/minecraft/client/shader/Framebuffer;Lcodechicken/lib/render/shader/ShaderObject;Ljava/util/function/Consumer;)Lnet/minecraft/client/shader/Framebuffer;", ordinal = 3, remap = false),
        remap = false,
        require = 0
    )
    private static Framebuffer actinium$skipEmptyGroupInputComposite(
        final Framebuffer source, final ShaderObject shader,
        final Consumer<ShaderProgram.UniformCache> callback
    ) {
        return BloomSubmissionState.lastGroupHasContent()
            ? Shaders.renderFullImageInFBO(source, shader, callback)
            : source;
    }

    @Redirect(
        method = {
            "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            "renderBloomInternal(Lnet/minecraft/client/renderer/RenderGlobal;"
                + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I"
        },
        at = @At(value = "INVOKE", target = "Lgregtech/client/shader/Shaders;renderFullImageInFBO(Lnet/minecraft/client/shader/Framebuffer;Lcodechicken/lib/render/shader/ShaderObject;Ljava/util/function/Consumer;)Lnet/minecraft/client/shader/Framebuffer;", ordinal = 4, remap = false),
        remap = false,
        require = 0
    )
    private static Framebuffer actinium$skipEmptyGroupComposite(
        final Framebuffer source, final ShaderObject shader,
        final Consumer<ShaderProgram.UniformCache> callback
    ) {
        return BloomSubmissionState.lastGroupHasContent()
            ? Shaders.renderFullImageInFBO(source, shader, callback)
            : source;
    }
}
