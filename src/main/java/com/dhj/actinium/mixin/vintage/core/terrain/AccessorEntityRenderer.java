package com.dhj.actinium.mixin.vintage.core.terrain;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderer.class)
public interface AccessorEntityRenderer {
    @Accessor("fogColorRed")
    float celeritas$getFogColorRed();

    @Accessor("fogColorGreen")
    float celeritas$getFogColorGreen();

    @Accessor("fogColorBlue")
    float celeritas$getFogColorBlue();

    @Accessor("lightmapTexture")
    DynamicTexture getLightmapTexture();

    @Accessor("lightmapColors")
    int[] getLightmapColors();

    /**
     * Returns the update count of the renderer, which drives the weather texture scroll and reseeds
     * the rain particle stream.
     */
    @Accessor("rendererUpdateCount")
    int celeritas$getRendererUpdateCount();

    @Invoker("getNightVisionBrightness")
    float invokeGetNightVisionBrightness(EntityLivingBase entityLivingBase, float partialTicks);
}
