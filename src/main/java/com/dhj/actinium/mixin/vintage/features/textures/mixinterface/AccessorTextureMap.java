package com.dhj.actinium.mixin.vintage.features.textures.mixinterface;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the atlas path lookup used by the load-session resource prefetcher. */
@Mixin(TextureMap.class)
public interface AccessorTextureMap {
    @Invoker("getResourceLocation")
    ResourceLocation actinium$getResourceLocation(TextureAtlasSprite sprite);
}
