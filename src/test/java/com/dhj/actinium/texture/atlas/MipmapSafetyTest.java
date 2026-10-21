package com.dhj.actinium.texture.atlas;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies which sprites may have their mipmaps built on a worker thread.
 */
class MipmapSafetyTest {

    private static class PlainSprite extends TextureAtlasSprite {

        PlainSprite() {
            super("plain");
        }
    }

    @Test
    void theVanillaClassIsSafe() {
        assertTrue(MipmapSafety.isSafe(TextureAtlasSprite.class));
    }

    /**
     * Offloading happens through the vanilla implementation only. A subclass may replace how frames are built
     * or stored, and Actinium cannot tell that apart without the reflective scan it does not use.
     */
    @Test
    void everySubclassStaysOnTheMainThread() {
        assertFalse(MipmapSafety.isSafe(PlainSprite.class));
    }
}
