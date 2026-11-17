package com.dhj.actinium.mixin.features.iris;

import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the brightness helpers {@code RenderLivingBase.renderLayers} calls around every layer.
 * The deferred overlay replay runs outside that loop and has to re-apply them itself.
 */
@Mixin(RenderLivingBase.class)
public interface AccessorRenderLivingBaseBrightness {

    @Invoker("setBrightness")
    boolean actinium$invokeSetBrightness(EntityLivingBase entity, float partialTicks, boolean combineTextures);

    @Invoker("unsetBrightness")
    void actinium$invokeUnsetBrightness();
}
