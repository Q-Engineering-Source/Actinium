package com.dhj.actinium.mixin.vintage.core.terrain;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code GlStateManager.TextureState.textureName}, the per-unit bound texture id
 * mirror that third-party mods (Mobends) read reflectively; GLSM owns the authoritative
 * binding cache and this mirror must be written back so their reflective reads see the real
 * binding (see {@code VanillaTextureMirrorCompat.createCallback}, registered from
 * {@code Actinium.onConstruct}). The target class is package-private, so it is targeted by
 * JVM internal name and the interface only references {@code int}.
 */
@Mixin(targets = "net/minecraft/client/renderer/GlStateManager$TextureState")
public interface AccessorGlStateManagerTextureState {
    @Accessor("textureName")
    int celeritas$getTextureName();

    @Accessor("textureName")
    void celeritas$setTextureName(int textureName);
}
