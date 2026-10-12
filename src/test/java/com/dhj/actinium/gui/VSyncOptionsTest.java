package com.dhj.actinium.gui;

import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the two decisions the three-state vsync option makes about a stored mode: which modes are
 * offered, and what an undeliverable stored mode reads as.
 *
 * <p>Both are driven through the support predicate the real option reads the backend through, so the
 * test states the rule rather than a particular backend's answer.</p>
 */
class VSyncOptionsTest {

    private static final Predicate<VSyncMode> NO_MAILBOX = mode -> mode != VSyncMode.MAILBOX;
    private static final Predicate<VSyncMode> EVERYTHING = mode -> true;
    private static final Predicate<VSyncMode> NOTHING = mode -> false;

    @Test
    void modesAreOfferedLowestLatencyFirst() {
        assertArrayEquals(new VSyncMode[] { VSyncMode.OFF, VSyncMode.ON, VSyncMode.MAILBOX }, VSyncOptions.selectableModes(EVERYTHING));
        assertArrayEquals(new VSyncMode[] { VSyncMode.OFF, VSyncMode.ON }, VSyncOptions.selectableModes(NO_MAILBOX));
    }

    @Test
    void aBackendThatOffersNothingStillLetsVsyncBeTurnedOn() {
        assertArrayEquals(new VSyncMode[] { VSyncMode.ON }, VSyncOptions.selectableModes(NOTHING));
    }

    @Test
    void anUndeliverableStoredModeReadsAsPlainVsync() {
        assertEquals(VSyncMode.ON, VSyncOptions.supportedMode(VSyncMode.MAILBOX, NO_MAILBOX));
    }

    @Test
    void aDeliverableStoredModeIsKept() {
        assertEquals(VSyncMode.MAILBOX, VSyncOptions.supportedMode(VSyncMode.MAILBOX, EVERYTHING));
        assertEquals(VSyncMode.OFF, VSyncOptions.supportedMode(VSyncMode.OFF, EVERYTHING));
        assertEquals(VSyncMode.ON, VSyncOptions.supportedMode(VSyncMode.ON, NO_MAILBOX));
    }

    @Test
    void everyModeHasATranslationKeyNamedAfterIt() {
        assertEquals("sodium.options.vsync_mode.off", VSyncOptions.modeKey(VSyncMode.OFF));
        assertEquals("sodium.options.vsync_mode.on", VSyncOptions.modeKey(VSyncMode.ON));
        assertEquals("sodium.options.vsync_mode.mailbox", VSyncOptions.modeKey(VSyncMode.MAILBOX));
    }
}
