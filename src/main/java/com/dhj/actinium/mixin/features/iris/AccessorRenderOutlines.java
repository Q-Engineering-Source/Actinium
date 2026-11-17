package com.dhj.actinium.mixin.features.iris;

import net.minecraft.client.renderer.entity.Render;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the outline flag {@code Render.doRender} branches on.
 *
 * <p>The outline branch of {@code RenderLivingBase.doRender} also walks the layer list, but it
 * draws into the entity outline framebuffer; a deferred overlay would land in the wrong target.</p>
 */
@Mixin(Render.class)
public interface AccessorRenderOutlines {

    @Accessor("renderOutlines")
    boolean actinium$isRenderOutlines();
}
