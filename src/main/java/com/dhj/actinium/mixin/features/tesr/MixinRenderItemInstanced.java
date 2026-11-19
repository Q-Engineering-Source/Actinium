package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.GlintClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reads the enchantment glint animation from the frame clock instead of the wall clock.
 *
 * <p>Every glint draw in a frame has to use one time value: the batched glint layers compute their
 * texture matrices once per frame in {@link GlintClock}, and a wall-clock read per draw would place
 * the immediate and the batched layers of the same item at different offsets. The frame clock is
 * the same value the batch captured at the start of the frame.</p>
 *
 * <p>This mixin only hands the frame clock to the glint texture matrix. Item instancing lives in the
 * dropped item path and is not part of this class.</p>
 */
@Mixin(RenderItem.class)
public abstract class MixinRenderItemInstanced {

    @Redirect(
        method = "renderEffect",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getSystemTime()J")
    )
    private long actinium$frameGlintClock() {
        return GlintClock.millis();
    }
}
