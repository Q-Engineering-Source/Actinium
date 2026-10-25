package com.dhj.actinium.mixin.mod.lumenized;

import com.dhj.actinium.compat.lumenized.BloomStateGuard;
import gregtech.client.utils.BloomEffectUtil;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets the bloom pass with a GLSM state snapshot/restore: the pass re-binds framebuffers,
 * runs external CCL shader programs and re-points texture units 0/1 at its own FBO textures,
 * and whatever it fails to restore leaks into the passes that follow the translucent terrain
 * pass (first-person hand and held item render fully black). The public entry method covers
 * both pass layouts: Lumenized keeps {@code renderBloomBlockLayer} inline, GregTech CEu 2.8
 * splits it into the wrapper plus {@code renderBloomInternal}.
 */
@Mixin(value = BloomEffectUtil.class, remap = false)
public abstract class MixinBloomEffectUtilStateGuard {

    @Inject(
        method = "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
            + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
        at = @At("HEAD"),
        remap = false
    )
    private static void actinium$pushBloomGlState(
        RenderGlobal renderGlobal,
        BlockRenderLayer layer,
        double partialTicks,
        int pass,
        Entity entity,
        CallbackInfoReturnable<Integer> cir
    ) {
        BloomStateGuard.push();
    }

    @Inject(
        method = "renderBloomBlockLayer(Lnet/minecraft/client/renderer/RenderGlobal;"
            + "Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
        at = @At("RETURN"),
        remap = false
    )
    private static void actinium$popBloomGlState(
        RenderGlobal renderGlobal,
        BlockRenderLayer layer,
        double partialTicks,
        int pass,
        Entity entity,
        CallbackInfoReturnable<Integer> cir
    ) {
        BloomStateGuard.pop();
    }
}
