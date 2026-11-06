package com.dhj.actinium.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;

import java.util.List;

/**
 * Advances an animated sprite's frame counters without uploading the new frame.
 *
 * <p>A sprite that was not marked visible keeps its animation position so it resumes in the right
 * frame once it reappears, while the texture upload is skipped for the frames it spent hidden.
 */
public final class SpriteAnimationDryRun {

    private SpriteAnimationDryRun() {
    }

    public static void advance(TextureAtlasSprite sprite) {
        final AnimationMetadataSection metadata = sprite.animationMetadata;
        final List<int[][]> frames = sprite.framesTextureData;
        if (metadata == null || frames == null) {
            return;
        }

        sprite.tickCounter++;
        if (sprite.tickCounter >= metadata.getFrameTimeSingle(sprite.frameCounter)) {
            final int frameCount = metadata.getFrameCount() == 0 ? frames.size() : metadata.getFrameCount();
            sprite.frameCounter = (sprite.frameCounter + 1) % frameCount;
            sprite.tickCounter = 0;
        }
    }
}
