package dhj.embeddedt.embeddium.impl.common.util;

/** Releases worker-local scratch only after two consecutive low-use windows. */
public final class ScratchRetentionWindow {
    private static volatile boolean enabled = true;
    private int samples;
    private long peakUsedBytes;
    private boolean previousWindowLow;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(final boolean enabled) {
        ScratchRetentionWindow.enabled = enabled;
    }

    public boolean endTask(final long usedBytes, final long capacityBytes, final long initialCapacityBytes) {
        peakUsedBytes = Math.max(peakUsedBytes, usedBytes);
        if (++samples < 64) {
            return false;
        }

        final long allowance = Math.max(initialCapacityBytes,
            peakUsedBytes > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : peakUsedBytes * 4);
        final boolean lowUse = capacityBytes > allowance;
        final boolean release = lowUse && previousWindowLow;
        previousWindowLow = lowUse && !release;
        samples = 0;
        peakUsedBytes = 0;
        return release;
    }
}
