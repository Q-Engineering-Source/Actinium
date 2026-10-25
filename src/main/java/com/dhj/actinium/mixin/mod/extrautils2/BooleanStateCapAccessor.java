package com.dhj.actinium.mixin.mod.extrautils2;

import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the GL capability constant of a vanilla {@code GlStateManager.BooleanState}
 * so the Extra Utilities 2 state snapshot can re-read live GL state (issue #48): the
 * vanilla field mirror is stale under the GLSM redirect, and re-querying each boolean
 * state by its capability keeps the snapshot in sync with the real GL state.
 */
@Mixin(GlStateManager.BooleanState.class)
public interface BooleanStateCapAccessor {
    @Accessor("capability")
    int getCap();
}