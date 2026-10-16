package com.gtnewhorizons.angelica.sdlgpu.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackFrameProbeTest {

    @Test
    void isolatedBlackFrameReportsOnce() {
        final BlackFrameProbe.RunTracker tracker = new BlackFrameProbe.RunTracker();
        tracker.onPixel(1, false);
        tracker.onPixel(2, true);
        assertEquals(2, tracker.onPixel(3, false));
        assertEquals(0, tracker.onPixel(4, false));
    }

    @Test
    void sustainedBlackRunDoesNotReport() {
        final BlackFrameProbe.RunTracker tracker = new BlackFrameProbe.RunTracker();
        tracker.onPixel(1, true);
        tracker.onPixel(2, true);
        tracker.onPixel(3, true);
        assertEquals(0, tracker.onPixel(4, false));
    }

    @Test
    void newIsolatedRunReportsAgain() {
        final BlackFrameProbe.RunTracker tracker = new BlackFrameProbe.RunTracker();
        tracker.onPixel(1, true);
        assertEquals(1, tracker.onPixel(2, false));
        tracker.onPixel(3, true);
        assertEquals(3, tracker.onPixel(4, false));
    }

    @Test
    void blackPixelClassification() {
        assertTrue(BlackFrameProbe.isBlack(0xFF000000));
        assertTrue(BlackFrameProbe.isBlack(0x00000000));
        assertFalse(BlackFrameProbe.isBlack(0xFF0000FF));
        assertFalse(BlackFrameProbe.isBlack(0xFF080808));
    }
}
