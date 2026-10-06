package dhj.embeddedt.embeddium.impl.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScratchRetentionWindowTest {
    @Test
    void releasesAfterTwoConsecutiveLowUseWindows() {
        final ScratchRetentionWindow retention = new ScratchRetentionWindow();

        assertFalse(sampleWindow(retention, 1_000, 65_536, 16_384));
        assertTrue(sampleWindow(retention, 1_000, 65_536, 16_384));
    }

    @Test
    void highUseWindowBreaksTheLowUseStreak() {
        final ScratchRetentionWindow retention = new ScratchRetentionWindow();

        assertFalse(sampleWindow(retention, 1_000, 65_536, 16_384));
        assertFalse(sampleWindow(retention, 20_000, 65_536, 16_384));
        assertFalse(sampleWindow(retention, 1_000, 65_536, 16_384));
        assertTrue(sampleWindow(retention, 1_000, 65_536, 16_384));
    }

    private static boolean sampleWindow(final ScratchRetentionWindow retention, final long usedBytes,
                                        final long capacityBytes, final long initialCapacityBytes) {
        boolean release = false;
        for (int i = 0; i < 64; i++) {
            release = retention.endTask(usedBytes, capacityBytes, initialCapacityBytes);
        }
        return release;
    }
}
