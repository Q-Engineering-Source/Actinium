package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.render.iris.SelectionBoxOutlinePhase;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Wraps selection-box rendering so Iris always releases its outline phase, including when another
 * mixin cancels the method body before it reaches RETURN.
 */
@Mixin(value = RenderGlobal.class, priority = 1100)
public abstract class RenderGlobalSelectionOutlineMixin {
    /**
     * Runs after ReplayMod's default-priority HEAD cancellation, keeping its early return inside
     * the outline phase's cleanup boundary.
     */
    @WrapMethod(method = "drawSelectionBox(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/math/RayTraceResult;IF)V")
    private void actinium$wrapSelectionBoxWithOutlinePhase(
        EntityPlayer player,
        RayTraceResult result,
        int execute,
        float partialTicks,
        Operation<Void> original
    ) {
        SelectionBoxOutlinePhase.runWithOutline(() -> original.call(player, result, execute, partialTicks));
    }
}
