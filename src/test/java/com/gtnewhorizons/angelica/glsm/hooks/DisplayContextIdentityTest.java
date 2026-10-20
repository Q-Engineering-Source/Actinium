package com.gtnewhorizons.angelica.glsm.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the judgement that decides when the GL state manager announces a display-context change.
 *
 * <p>Two things hang on it: the driver fingerprint the program-binary cache keys every saved program on, and
 * the pool of retained program names. Both are dropped when a change is reported, so reporting one that did
 * not happen throws away work, and missing a real one reuses names that mean something else on the new
 * context.</p>
 */
class DisplayContextIdentityTest {

    /** A handle a backend could report for the startup context. */
    private static final long FIRST_CONTEXT = 0xC0FFEEL;
    /** A handle a splash replacement could report after migrating the game. */
    private static final long SPLASH_CONTEXT = 0xBEEFL;

    @Test
    void theFirstHandleIsNotAChange() {
        assertFalse(DisplayContextIdentity.isContextChange(0L, FIRST_CONTEXT),
            "the startup context recording itself for the first time is not a migration");
    }

    @Test
    void theSameHandleIsNotAChange() {
        assertFalse(DisplayContextIdentity.isContextChange(FIRST_CONTEXT, FIRST_CONTEXT),
            "running twice on the same context must not invalidate its caches");
    }

    @Test
    void aHandleThatIsNoLongerReportedIsNotAChange() {
        assertFalse(DisplayContextIdentity.isContextChange(FIRST_CONTEXT, 0L),
            "a backend that stopped reporting a handle says nothing about the context changing");
        assertFalse(DisplayContextIdentity.isContextChange(0L, 0L),
            "two backends without a queryable handle cannot be told apart");
    }

    @Test
    void twoEstablishedDifferentHandlesAreAChange() {
        assertTrue(DisplayContextIdentity.isContextChange(FIRST_CONTEXT, SPLASH_CONTEXT),
            "a second init on another context is what a splash replacement migration looks like");
    }
}
