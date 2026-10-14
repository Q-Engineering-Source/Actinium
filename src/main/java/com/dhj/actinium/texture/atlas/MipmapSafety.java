package com.dhj.actinium.texture.atlas;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Whether a sprite's mipmap generation may run on a worker thread. An explicit allow list - the vanilla class
 * only - instead of upstream's reflective override scan, which project rules forbid; modded subclasses keep
 * their mipmaps on the main thread. The other half of the decision (who may read the frames meanwhile) is
 * {@link AtlasLoadPlan}.
 */
public final class MipmapSafety {

    private MipmapSafety() {
    }

    public static boolean isSafe(Class<?> spriteClass) {
        return spriteClass == TextureAtlasSprite.class;
    }
}
