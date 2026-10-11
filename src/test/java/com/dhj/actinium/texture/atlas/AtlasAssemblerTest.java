package com.dhj.actinium.texture.atlas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the band arithmetic the batched atlas upload splits the atlas with.
 */
class AtlasAssemblerTest {

    /**
     * Every band has to start on a multiple of the atlas's mip alignment, otherwise a level's origin would
     * land between texels, and it has to stay small enough that a staging buffer covers a band's rows.
     */
    @ParameterizedTest(name = "bands of a {0}x{1} atlas at alignment {2}")
    @CsvSource({
        "4096, 4096, 1",
        "4096, 4096, 16",
        "16384, 16384, 16",
        "256, 256, 4",
        "512, 128, 16"
    })
    void bandsStayAlignedAndWithinTheAtlas(int atlasWidth, int atlasHeight, int align) {
        final int bandRows = AtlasAssembler.bandRowsFor(atlasWidth, atlasHeight, align);

        assertEquals(0, bandRows % align, "band height must be a multiple of the mip alignment");
        assertTrue(bandRows >= align, "a band must hold at least one aligned row set");
        assertTrue(bandRows <= Math.max(atlasHeight, align), "bands must not exceed the atlas");
    }

    @Test
    void aSpriteSpanningAFailedBandIsNotCovered() {
        final boolean[] bandOk = { true, false, true };

        assertTrue(AtlasAssembler.covered(bandOk, 16, 0, 16), "a sprite inside one written band is covered");
        assertTrue(AtlasAssembler.covered(bandOk, 16, 32, 48), "the same holds for later bands");
        assertFalse(AtlasAssembler.covered(bandOk, 16, 8, 24), "a sprite crossing into a failed band is not");
        assertFalse(AtlasAssembler.covered(bandOk, 16, 0, 48), "nor is one spanning the whole failed band");
    }
}
