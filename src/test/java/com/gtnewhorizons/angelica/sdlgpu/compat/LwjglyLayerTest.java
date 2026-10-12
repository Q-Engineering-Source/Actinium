package com.gtnewhorizons.angelica.sdlgpu.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the layer probe that gates the whole SDL enable path.
 *
 * <p>The probe answers the backend's first prerequisite: on a host without LWJGLY there is no window
 * to claim, and the answer has to arrive without loading a single {@code com.cleanroommc.lwjgly.*}
 * class. The test classpath carries LWJGLY's SPI, so the positive branch runs here; the negative one
 * is asserted through a loader that cannot see anything, which is what the probe must answer for an
 * old-Cleanroom run.</p>
 */
@Timeout(10)
class LwjglyLayerTest {

    private static final String LWJGLY = "com.cleanroommc.lwjgly.LWJGLY";

    @Test
    void findsLwjglyOnTheClasspath() {
        assertTrue(LwjglyLayer.present(LWJGLY, LwjglyLayerTest.class.getClassLoader()));
        assertTrue(LwjglyLayer.present());
    }

    @Test
    void reportsLwjglyAsAbsentToALoaderThatCannotSeeIt() {
        // A loader with no parent can only reach the bootstrap classpath, so the layer is
        // definitively not visible through it.
        assertFalse(LwjglyLayer.present(LWJGLY, new ClassLoader(null) {}));
    }
}
