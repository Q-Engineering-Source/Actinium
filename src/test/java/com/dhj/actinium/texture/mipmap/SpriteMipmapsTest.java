package com.dhj.actinium.texture.mipmap;

import dhj.embeddedt.embeddium.api.util.ColorARGB;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-sprite facts the mipmap strategy is chosen from.
 */
class SpriteMipmapsTest {

    private static final int OPAQUE = ColorARGB.pack(10, 20, 30, 255);
    private static final int TRANSLUCENT = ColorARGB.pack(10, 20, 30, 128);
    private static final int TRANSPARENT = ColorARGB.pack(0, 0, 0, 0);

    @Test
    void fullyOpaqueFramesReportOpaque() {
        final SpriteMipmaps.Transparency transparency = SpriteMipmaps.scan(framesOf(new int[] {OPAQUE, OPAQUE}));

        assertEquals(SpriteTransparencyLevel.OPAQUE, transparency.level());
        assertFalse(transparency.hasTransparentTexels());
    }

    /** A texel that is transparent but not fully so is translucent, which is a level of its own. */
    @Test
    void semiTransparentTexelsReportTranslucent() {
        final SpriteMipmaps.Transparency transparency = SpriteMipmaps.scan(framesOf(new int[] {OPAQUE, TRANSLUCENT}));

        assertEquals(SpriteTransparencyLevel.TRANSLUCENT, transparency.level());
        assertFalse(transparency.hasTransparentTexels());
    }

    /** Only a fully transparent texel makes a sprite a cutout, and it outranks the translucent texels. */
    @Test
    void fullyTransparentTexelsReportTransparentAndFlagTheCutout() {
        final SpriteMipmaps.Transparency transparentOnly = SpriteMipmaps.scan(framesOf(new int[] {OPAQUE, TRANSPARENT}));
        assertEquals(SpriteTransparencyLevel.TRANSPARENT, transparentOnly.level());
        assertTrue(transparentOnly.hasTransparentTexels());

        final SpriteMipmaps.Transparency both =
            SpriteMipmaps.scan(framesOf(new int[] {TRANSLUCENT, TRANSPARENT}));
        assertEquals(SpriteTransparencyLevel.TRANSLUCENT, both.level());
        assertTrue(both.hasTransparentTexels());
    }

    /** An animation is classified over all of its frames, since any of them can be the one on screen. */
    @Test
    void everyFrameIsScanned() {
        final List<int[][]> frames = framesOf(new int[] {OPAQUE});
        frames.add(new int[][] {new int[] {TRANSPARENT}});

        final SpriteMipmaps.Transparency transparency = SpriteMipmaps.scan(frames);

        assertEquals(SpriteTransparencyLevel.TRANSPARENT, transparency.level());
        assertTrue(transparency.hasTransparentTexels());
    }

    /**
     * A sprite whose frames never loaded keeps the level it starts at, which is the conservative one: nothing
     * with no pixels should be demoted into a pass that its texture may not belong to.
     */
    @Test
    void missingFramesReportTranslucentAndNoCutout() {
        final List<int[][]> empty = new ArrayList<>();
        empty.add(null);
        empty.add(new int[0][]);

        assertEquals(SpriteTransparencyLevel.TRANSLUCENT, SpriteMipmaps.scan(null).level());
        assertFalse(SpriteMipmaps.scan(null).hasTransparentTexels());
        assertEquals(SpriteTransparencyLevel.TRANSLUCENT, SpriteMipmaps.scan(Collections.emptyList()).level());
        assertEquals(SpriteTransparencyLevel.TRANSLUCENT, SpriteMipmaps.scan(empty).level());
        assertFalse(SpriteMipmaps.scan(empty).hasTransparentTexels());
    }

    /**
     * A mode a resource pack declared always applies; one that was only guessed from the sprite's name is
     * dropped once the sprite turns out not to be a cutout, because then its premise is gone.
     */
    @Test
    void declaredModesSurviveTheCutoutCheckAndGuessesDoNot() {
        assertNull(SpriteMipmaps.resolve(MipmapStrategy.DARK_CUTOUT, false, false));
        assertNull(SpriteMipmaps.resolve(MipmapStrategy.MEAN, false, false));

        assertEquals(MipmapStrategy.DARK_CUTOUT, SpriteMipmaps.resolve(MipmapStrategy.DARK_CUTOUT, false, true));
        assertEquals(MipmapStrategy.MEAN, SpriteMipmaps.resolve(MipmapStrategy.MEAN, false, true));

        assertEquals(MipmapStrategy.MEAN, SpriteMipmaps.resolve(MipmapStrategy.MEAN, true, false));
        assertEquals(MipmapStrategy.STRICT_CUTOUT, SpriteMipmaps.resolve(MipmapStrategy.STRICT_CUTOUT, true, false));

        assertNull(SpriteMipmaps.resolve(null, false, true));
    }

    private static List<int[][]> framesOf(int[] pixels) {
        final List<int[][]> frames = new ArrayList<>();
        frames.add(new int[][] {pixels});
        return frames;
    }
}
