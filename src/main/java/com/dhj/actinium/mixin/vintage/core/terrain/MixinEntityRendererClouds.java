package com.dhj.actinium.mixin.vintage.core.terrain;

import com.gtnewhorizons.angelica.render.CloudRenderer;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Extends the projection of the cloud pass to the distance the cloud discs need.
 *
 * <p>{@code renderCloudsCheck} builds a dedicated projection for the cloud pass with four times the terrain
 * far plane and restores the terrain projection afterwards. Only the far argument of the cloud projection is
 * raised, and only when the cloud renderer needs more than the value the entry point computed, so the sky
 * render distance that produced that value and the restored projection both stay untouched.</p>
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererClouds {

    @ModifyArg(
        method = "renderCloudsCheck",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", ordinal = 0),
        index = 3
    )
    private float actinium$extendCloudFarPlane(float farPlane) {
        return Math.max(farPlane, CloudRenderer.getCloudRenderer().getRequiredFarPlaneDistance());
    }
}
