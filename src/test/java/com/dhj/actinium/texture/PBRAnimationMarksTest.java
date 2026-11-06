package com.dhj.actinium.texture;

import com.gtnewhorizons.angelica.mixins.interfaces.IPatchedTextureAtlasSprite;
import net.coderbot.iris.texture.pbr.PBRSpriteHolder;
import net.coderbot.iris.texture.pbr.TextureAtlasSpriteExtension;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PBR sprites are never sampled through sprite UVs, so they inherit the visibility mark their base
 * sprite consumes.
 */
class PBRAnimationMarksTest {

    @Test
    void marksBothSiblingSprites() {
        final BaseSprite base = new BaseSprite();
        final HookedSprite normal = new HookedSprite("minecraft:stone_n");
        final HookedSprite specular = new HookedSprite("minecraft:stone_s");
        base.holder = holderWith(normal, specular);

        PBRAnimationMarks.markFromBaseSprite(base);

        assertEquals(1, normal.marks, "the normal sprite inherits the base visibility");
        assertEquals(1, specular.marks, "the specular sprite inherits the base visibility");
    }

    @Test
    void ignoresSpritesWithoutPBRSiblings() {
        final BaseSprite base = new BaseSprite();
        base.holder = holderWith(new HookedSprite("minecraft:stone_n"), null);
        PBRAnimationMarks.markFromBaseSprite(base);

        assertEquals(0, base.holder.getSpecularSprite() == null ? 0 : 1, "a missing sibling is skipped");
    }

    @Test
    void doesNothingWithoutAHolder() {
        final BaseSprite base = new BaseSprite();

        PBRAnimationMarks.markFromBaseSprite(base);
    }

    private static PBRSpriteHolder holderWith(TextureAtlasSprite normal, TextureAtlasSprite specular) {
        final PBRSpriteHolder holder = new PBRSpriteHolder();
        if (normal != null) {
            holder.setNormalSprite(normal);
        }
        if (specular != null) {
            holder.setSpecularSprite(specular);
        }
        return holder;
    }

    private static final class BaseSprite extends TextureAtlasSprite implements TextureAtlasSpriteExtension {
        private PBRSpriteHolder holder;

        BaseSprite() {
            super("minecraft:stone");
        }

        @Override
        public PBRSpriteHolder getPBRHolder() {
            return holder;
        }

        @Override
        public PBRSpriteHolder getOrCreatePBRHolder() {
            return holder;
        }
    }

    private static final class HookedSprite extends TextureAtlasSprite implements IPatchedTextureAtlasSprite {
        private int marks;

        HookedSprite(String name) {
            super(name);
        }

        @Override
        public void markNeedsAnimationUpdate() {
            marks++;
        }

        @Override
        public boolean needsAnimationUpdate() {
            return false;
        }

        @Override
        public void unmarkNeedsAnimationUpdate() {
        }

        @Override
        public boolean tickAnimation(boolean onlyVisible) {
            return false;
        }
    }
}
