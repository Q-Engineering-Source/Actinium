package com.gtnewhorizons.angelica.mixins.interfaces;

import net.coderbot.iris.texture.SpriteAnimationTicker;

/**
 * Animation capability added to every atlas sprite by the texture mixins.
 *
 * <p>Sprites are marked visible when their UVs are sampled, and the atlas animation passes consume
 * that mark through {@link #tickAnimation(boolean)}. A sprite that was not marked keeps its frame
 * counters in step without uploading a frame.
 */
@SuppressWarnings("unused")
public interface IPatchedTextureAtlasSprite extends SpriteAnimationTicker {
    void markNeedsAnimationUpdate();

    boolean needsAnimationUpdate();

    void unmarkNeedsAnimationUpdate();

    @Override
    boolean tickAnimation(boolean onlyVisible);
}
