package net.coderbot.iris.texture.pbr;

import com.gtnewhorizons.angelica.compat.mojang.NativeImage;
import com.gtnewhorizons.angelica.glsm.profiling.DebugCounters;
import com.gtnewhorizons.angelica.mixins.interfaces.IPatchedTextureAtlasSprite;
import net.coderbot.iris.texture.pbr.loader.AtlasPBRLoader;
import net.coderbot.iris.texture.pbr.loader.TextureAtlasSpriteInfo;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the PBR atlas animation pass without a GL context. The atlas decides per animated sprite
 * whether the frame is uploaded, only advanced, or skipped entirely, and reports both outcomes
 * through the debug counters.
 */
class PBRAtlasAnimationTest {
    private static final int SPRITE_SIZE = 16;
    private static final int ATLAS_SIZE = 64;
    private static final int ANIMATED_FRAMES = 2;

    @Test
    void visibleTrackedSpriteUploadsItsFrame() {
        final TrackedSprite sprite = new TrackedSprite("minecraft:stone_n", new BaseSprite("minecraft:stone", true),
            true, true);
        final Delta delta = cycle(sprite, true);

        assertEquals(1, sprite.ticks, "the tracked sprite is ticked once");
        assertEquals(true, sprite.lastOnlyVisible, "the tick receives the visibility option");
        assertEquals(1, sprite.uploads, "a visible sprite uploads its frame");
        assertEquals(1L, delta.updates, "the upload is counted");
        assertEquals(0L, delta.dryRuns, "nothing was skipped");
    }

    @Test
    void hiddenTrackedSpriteOnlyAdvancesFrames() {
        final TrackedSprite sprite = new TrackedSprite("minecraft:stone_n", new BaseSprite("minecraft:stone", true),
            true, false);
        final Delta delta = cycle(sprite, true);

        assertEquals(1, sprite.ticks, "the hidden sprite is still ticked");
        assertEquals(true, sprite.lastOnlyVisible, "the tick receives the visibility option");
        assertEquals(0, sprite.uploads, "a hidden sprite must not upload");
        assertEquals(0L, delta.updates, "no upload happened");
        assertEquals(1L, delta.dryRuns, "the skipped upload is counted");
    }

    @Test
    void disabledVisibilityGateUploadsEveryTrackedSprite() {
        final TrackedSprite sprite = new TrackedSprite("minecraft:stone_n", new BaseSprite("minecraft:stone", true),
            false, false);
        final Delta delta = cycle(sprite, false);

        assertEquals(1, sprite.ticks, "the sprite is ticked");
        assertEquals(false, sprite.lastOnlyVisible, "the tick receives the disabled gate");
        assertEquals(1, sprite.uploads, "every sprite uploads while the gate is off");
        assertEquals(1L, delta.updates, "the upload is counted");
        assertEquals(0L, delta.dryRuns, "nothing was skipped");
    }

    @Test
    void baseWithoutAnimationMetadataAlwaysUploads() {
        final TrackedSprite sprite = new TrackedSprite("minecraft:stone_n", new BaseSprite("minecraft:stone", false),
            false, false);
        final Delta delta = cycle(sprite, true);

        assertEquals(0, sprite.ticks, "visibility tracking is not consulted without base animation metadata");
        assertEquals(1, sprite.uploads, "the sprite uploads unconditionally");
        assertEquals(1L, delta.updates, "the upload is counted");
        assertEquals(0L, delta.dryRuns, "nothing was skipped");
    }

    @Test
    void spriteWithoutTheAnimationPatchAlwaysUploads() {
        final PlainSprite sprite = new PlainSprite("minecraft:dirt_n", new BaseSprite("minecraft:dirt", true));
        final Delta delta = cycle(sprite, true);

        assertEquals(1, sprite.uploads, "a sprite without the animation patch uploads unconditionally");
        assertEquals(1L, delta.updates, "the upload is counted");
        assertEquals(0L, delta.dryRuns, "nothing was skipped");
    }

    @Test
    void pbrSpriteWithoutAnimationMetadataIsNeverTouched() {
        final TrackedSprite sprite = new TrackedSprite("minecraft:stone_n", new BaseSprite("minecraft:stone", true),
            false, false);
        sprite.clearAnimationMetadata();
        final Delta delta = cycle(sprite, true);

        assertEquals(0, sprite.ticks, "a single-frame PBR sprite has no animation to tick");
        assertEquals(0, sprite.uploads, "a single-frame PBR sprite is never uploaded");
        assertEquals(0L, delta.updates, "nothing was counted as updated");
        assertEquals(0L, delta.dryRuns, "nothing was counted as skipped");
    }

    private static Delta cycle(AtlasPBRLoader.PBRTextureAtlasSprite sprite, boolean onlyVisible) {
        final TestAtlas atlas = new TestAtlas();
        atlas.addSprite(sprite);

        final DebugCounters.Snapshot before = DebugCounters.snapshot();
        atlas.cycleAnimationFrames(onlyVisible);
        final DebugCounters.Delta delta = DebugCounters.snapshot().since(before);
        return new Delta(delta.pbrAnimUpdates(), delta.pbrAnimDryRuns());
    }

    private record Delta(long updates, long dryRuns) {
    }

    /** PBR atlas that skips the texture bind so the animation pass can run headless. */
    private static final class TestAtlas extends PBRAtlasTexture {
        TestAtlas() {
            super(new TextureMap("textures"), PBRType.NORMAL);
        }

        @Override
        public void bind() {
        }
    }
    /** PBR sprite without the animation patch, so its visibility can never be tracked. */
    private static class PlainSprite extends AtlasPBRLoader.PBRTextureAtlasSprite {
        protected int uploads;

        PlainSprite(String name, TextureAtlasSprite baseSprite) {
            super(spriteInfo(name), metadata(), ATLAS_SIZE, ATLAS_SIZE, 0, 0, image(), 0, baseSprite);
        }

        @Override
        public void updateAnimation() {
            uploads++;
        }

        void clearAnimationMetadata() {
            this.animationMetadata = null;
        }
    }

    /** PBR sprite that behaves like the animation mixins: one gate, one upload decision. */
    private static final class TrackedSprite extends PlainSprite implements IPatchedTextureAtlasSprite {
        private final boolean uploadWhenTicked;
        private int ticks;
        private boolean lastOnlyVisible;

        TrackedSprite(String name, TextureAtlasSprite baseSprite, boolean uploadWhenTicked, boolean consumeMark) {
            super(name, baseSprite);
            this.uploadWhenTicked = uploadWhenTicked;
            this.marked = consumeMark;
        }

        private boolean marked;

        @Override
        public boolean tickAnimation(boolean onlyVisible) {
            ticks++;
            lastOnlyVisible = onlyVisible;
            if (!onlyVisible || marked) {
                marked = false;
                updateAnimation();
                return true;
            }
            return false;
        }

        @Override
        public void markNeedsAnimationUpdate() {
            marked = true;
        }

        @Override
        public boolean needsAnimationUpdate() {
            final boolean wasMarked = marked;
            marked = false;
            return wasMarked;
        }

        @Override
        public void unmarkNeedsAnimationUpdate() {
            marked = false;
        }
    }

    /** Base atlas sprite; only its animation metadata matters to the PBR atlas. */
    private static final class BaseSprite extends TextureAtlasSprite {
        BaseSprite(String name, boolean animated) {
            super(name);
            if (animated) {
                this.animationMetadata = metadata();
            }
        }
    }

    private static TextureAtlasSpriteInfo spriteInfo(String name) {
        return new TextureAtlasSpriteInfo(new ResourceLocation(name), SPRITE_SIZE, SPRITE_SIZE);
    }

    private static AnimationMetadataSection metadata() {
        final List<AnimationFrame> animationFrames = new ArrayList<>(ANIMATED_FRAMES);
        for (int frame = 0; frame < ANIMATED_FRAMES; frame++) {
            animationFrames.add(new AnimationFrame(frame, -1));
        }
        return new AnimationMetadataSection(animationFrames, SPRITE_SIZE, SPRITE_SIZE, 1, false);
    }

    private static NativeImage image() {
        return new NativeImage(SPRITE_SIZE, SPRITE_SIZE * ANIMATED_FRAMES, false);
    }
}
