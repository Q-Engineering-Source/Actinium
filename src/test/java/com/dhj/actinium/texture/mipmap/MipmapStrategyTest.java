package com.dhj.actinium.texture.mipmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MipmapStrategyTest {

    @Test
    void byNameIsCaseInsensitive() {
        assertEquals(MipmapStrategy.MEAN, MipmapStrategy.byName("mean"));
        assertEquals(MipmapStrategy.MEAN, MipmapStrategy.byName("MEAN"));
        assertEquals(MipmapStrategy.DARK_CUTOUT, MipmapStrategy.byName("Dark_Cutout"));
        assertEquals(MipmapStrategy.STRICT_CUTOUT, MipmapStrategy.byName("strict_cutout"));
        assertEquals(MipmapStrategy.AUTO, MipmapStrategy.byName("auto"));
    }

    @Test
    void unknownOrMissingNameIsNull() {
        assertNull(MipmapStrategy.byName(null));
        assertNull(MipmapStrategy.byName(""));
        assertNull(MipmapStrategy.byName("bicubic"));
    }

    /** The tie-break between conflicting rules has to be a total order so the outcome cannot depend on rule order. */
    @Test
    void conflictPrecedenceIsStrictlyOrdered() {
        assertTrue(MipmapStrategy.MEAN.conflictPrecedence() > MipmapStrategy.DARK_CUTOUT.conflictPrecedence());
        assertTrue(MipmapStrategy.DARK_CUTOUT.conflictPrecedence() > MipmapStrategy.STRICT_CUTOUT.conflictPrecedence());
        assertTrue(MipmapStrategy.STRICT_CUTOUT.conflictPrecedence() > MipmapStrategy.CUTOUT.conflictPrecedence());
        assertTrue(MipmapStrategy.CUTOUT.conflictPrecedence() > MipmapStrategy.AUTO.conflictPrecedence());
    }
}
