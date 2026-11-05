package com.gtnewhorizons.angelica.glsm.backend;

/**
 * Presentation mode requested from the window system.
 *
 * <p>The mode decides whether the driver owns the frame cadence or the pacer has to fill it in software. {@link #ON}
 * blocks inside the swap call until the next vblank, {@link #OFF} presents immediately, and {@link #MAILBOX} replaces
 * the queued frame with the newest one without blocking.
 */
public enum VSyncMode {
    OFF,
    ON,
    MAILBOX;

    /** Returns whether the mode prevents tearing, which is the same as requiring a vblank-aligned presentation. */
    public boolean tearFree() {
        return this != OFF;
    }
}
