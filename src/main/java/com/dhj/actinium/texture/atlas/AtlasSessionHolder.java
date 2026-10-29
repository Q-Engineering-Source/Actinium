package com.dhj.actinium.texture.atlas;

/**
 * Exposes the atlas session of the load in progress to the other TextureMap mixins, which own their own
 * injection state but have to tell whether the batched upload already wrote a sprite.
 */
public interface AtlasSessionHolder {

    AtlasLoadSession angelica$atlasSession();
}
