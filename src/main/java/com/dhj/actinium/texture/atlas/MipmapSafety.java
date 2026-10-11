package com.dhj.actinium.texture.atlas;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Decides whether a sprite's mipmap generation may run on a worker thread.
 *
 * <p>Upstream scans each sprite class for overrides of {@code generateMipmaps} and
 * {@code setFramesTextureData} and offloads everything that does not declare one. That scan is reflective,
 * which Actinium's rules forbid for business logic, so this is an explicit allow list instead: only the
 * vanilla class is offloaded and every modded subclass keeps its mipmaps on the main thread. The result is a
 * strict subset of upstream's parallel work and cannot mis-handle a subclass that changes how frames are
 * stored.
 */
public final class MipmapSafety {

    private MipmapSafety() {
    }

    public static boolean isSafe(Class<?> spriteClass) {
        return spriteClass == TextureAtlasSprite.class;
    }
}
