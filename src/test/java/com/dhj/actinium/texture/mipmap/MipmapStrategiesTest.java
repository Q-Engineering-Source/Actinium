package com.dhj.actinium.texture.mipmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MipmapStrategiesTest {

    @Test
    void vanillaLeafSpritesAreDarkCutout() {
        assertEquals(MipmapStrategy.DARK_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/leaves_oak"));
        assertEquals(MipmapStrategy.DARK_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/leaves_oak_opaque"));
        assertEquals(MipmapStrategy.DARK_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/leaves_spruce"));
    }

    @Test
    void vanillaFlowerAndMushroomSpritesAreStrictCutout() {
        assertEquals(MipmapStrategy.STRICT_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/flower_dandelion"));
        assertEquals(MipmapStrategy.STRICT_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/flower_rose"));
        assertEquals(MipmapStrategy.STRICT_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/flower_tulip_red"));
        assertEquals(MipmapStrategy.STRICT_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/mushroom_red"));
        assertEquals(MipmapStrategy.STRICT_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/mushroom_brown"));
    }

    @Test
    void vanillaGlassAndRedstoneSpritesAreMean() {
        assertEquals(MipmapStrategy.MEAN, MipmapStrategies.strategyFor("minecraft:blocks/glass"));
        assertEquals(MipmapStrategy.MEAN, MipmapStrategies.strategyFor("minecraft:blocks/glass_black"));
        assertEquals(MipmapStrategy.MEAN, MipmapStrategies.strategyFor("minecraft:blocks/redstone_dust_line0"));
        assertEquals(MipmapStrategy.MEAN, MipmapStrategies.strategyFor("minecraft:blocks/redstone_dust_dot"));
    }

    /** Sprites outside the naming families get no fallback, so the generator resolves AUTO from the pixels. */
    @Test
    void unrelatedSpritesHaveNoFallback() {
        assertNull(MipmapStrategies.strategyFor("minecraft:blocks/stone"));
        assertNull(MipmapStrategies.strategyFor("minecraft:blocks/oak_planks"));
        assertNull(MipmapStrategies.strategyFor(null));
    }

    /**
     * Block and item sprites share one atlas in 1.12.2, so the rules are scoped to {@code blocks/}. An item
     * whose name happens to carry a family marker must not be caught by it.
     */
    @Test
    void itemSpritesAreNeverMatched() {
        assertNull(MipmapStrategies.strategyFor("minecraft:items/glass_bottle"));
        assertNull(MipmapStrategies.strategyFor("minecraft:items/flower_pot"));
        assertNull(MipmapStrategies.strategyFor("minecraft:items/apple"));
    }

    /** A mod may not namespace its sprites; the path is then the whole icon name. */
    @Test
    void unnamespacedBlockPathsStillMatch() {
        assertEquals(MipmapStrategy.DARK_CUTOUT, MipmapStrategies.strategyFor("blocks/leaves_oak"));
    }

    /** When two families claim the same sprite, the mode that changes the image the most wins. */
    @Test
    void conflictingRulesResolveByPrecedence() {
        assertEquals(MipmapStrategy.MEAN, MipmapStrategies.strategyFor("minecraft:blocks/flower_glass"));
        assertEquals(MipmapStrategy.DARK_CUTOUT, MipmapStrategies.strategyFor("minecraft:blocks/leaves_mushroom"));
    }
}
