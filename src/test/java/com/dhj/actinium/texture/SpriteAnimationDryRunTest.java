package com.dhj.actinium.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A sprite that was not marked visible keeps its animation position without uploading a frame, so it
 * resumes on the same frame the full animation pass would have reached.
 */
class SpriteAnimationDryRunTest {
    private static final int FRAMES = 3;
    private static final int FRAME_TIME = 4;
    private static final int TICKS = 20;

    @Test
    void advancesFrameCountersExactlyLikeARealAnimationTick() {
        final Sprite dryRun = new Sprite();
        final Sprite uploaded = new Sprite();

        for (int tick = 0; tick < TICKS; tick++) {
            SpriteAnimationDryRun.advance(dryRun);
            uploaded.updateAnimation();

            assertEquals(uploaded.frameCounter, dryRun.frameCounter, "frame index after tick " + tick);
            assertEquals(uploaded.tickCounter, dryRun.tickCounter, "tick counter after tick " + tick);
        }
    }

    @Test
    void leavesSingleFrameSpritesAlone() {
        final Sprite sprite = new Sprite();
        sprite.animationMetadata = null;

        SpriteAnimationDryRun.advance(sprite);

        assertEquals(0, sprite.frameCounter);
        assertEquals(0, sprite.tickCounter);
    }

    private static final class Sprite extends TextureAtlasSprite {
        Sprite() {
            super("minecraft:stone");
            this.animationMetadata = metadata();
        }

        @Override
        public void updateAnimation() {
            // Frame upload is skipped by vanilla when the frame data is absent, so the counters
            // advance without touching GL.
            super.updateAnimation();
        }
    }

    private static AnimationMetadataSection metadata() {
        final List<AnimationFrame> animationFrames = new ArrayList<>(FRAMES);
        for (int frame = 0; frame < FRAMES; frame++) {
            animationFrames.add(new AnimationFrame(frame, -1));
        }
        return new AnimationMetadataSection(animationFrames, 16, 16, FRAME_TIME, false);
    }
}
