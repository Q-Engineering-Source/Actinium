package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the terrain texture filtering policy that the block atlas sampler is configured from.
 */
class TerrainTextureFilteringTest {

    /**
     * Anisotropic filtering rides on an extension that is not guaranteed in the GL version Actinium targets,
     * so a driver without it must fall back rather than ask for an amount it cannot honour.
     */
    @Test
    void unsupportedAnisotropyFallsBackToNoFiltering() {
        assertEquals(TextureFilterMode.NONE, TerrainTextureFiltering.resolveFilterMode(TextureFilterMode.ANISOTROPIC, false));
        assertEquals(TextureFilterMode.ANISOTROPIC, TerrainTextureFiltering.resolveFilterMode(TextureFilterMode.ANISOTROPIC, true));
        assertEquals(TextureFilterMode.NONE, TerrainTextureFiltering.resolveFilterMode(TextureFilterMode.NONE, false));
        assertEquals(TextureFilterMode.NONE, TerrainTextureFiltering.resolveFilterMode(TextureFilterMode.NONE, true));
    }

    /** A config written before the option existed has no value, and should read as the default mode. */
    @Test
    void missingModeResolvesToTheDefault() {
        assertEquals(TextureFilterMode.ANISOTROPIC, TerrainTextureFiltering.resolveFilterMode(null, true));
        assertEquals(TextureFilterMode.NONE, TerrainTextureFiltering.resolveFilterMode(null, false));
    }

    @Test
    void onlySupportedModesAreSelectable() {
        assertEquals(List.of(TextureFilterMode.ANISOTROPIC, TextureFilterMode.NONE),
            List.of(TextureFilterMode.selectableValues(true)));
        assertEquals(List.of(TextureFilterMode.NONE), List.of(TextureFilterMode.selectableValues(false)));
    }

    /** The option stores a log2 level, and the sampler wants the multiplier, floored at 2x and capped at the driver. */
    @ParameterizedTest(name = "level {0} with a driver maximum of {1} asks for {2}")
    @CsvSource({
        "1, 4, 2",
        "2, 4, 4",
        "4, 4, 16",
        "0, 4, 2",
        "7, 4, 16",
        "4, 1, 2",
        "3, 2, 4"
    })
    void anisotropyAmountIsClampedToTheDriverRange(int level, int maxAnisotropyLevel, int expected) {
        assertEquals(expected, TerrainTextureFiltering.anisotropyAmountFor(level, maxAnisotropyLevel));
    }

    @Test
    void anisotropyStartsAtTwoTimes() {
        assertEquals(1, TerrainTextureFiltering.minAnisotropyLevel());
    }
}
