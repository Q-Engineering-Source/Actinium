package com.gtnewhorizons.angelica.glsm.hooks;

/**
 * Decides when the display context has become a different one, which is the single moment every cache keyed
 * on the driver - and every pool of GL names created with it - stops describing anything reachable.
 *
 * <p>Splash replacements migrate the game to a context of their own (issue #150). The state manager notices
 * that both when it is initialized a second time and when it recreates the default vertex array, and both
 * callers need the same judgement, so it lives here instead of being spelled out twice.</p>
 */
public final class DisplayContextIdentity {

    /**
     * Reports whether the caller now runs on a different context than the objects it remembers.
     *
     * <p>Only two different handles are a change. A zero carries no identity: before a handle has been
     * recorded there is no earlier context anything could belong to, and a backend that cannot report one
     * gives no way to tell contexts apart. Treating either zero as a change would drop caches that still
     * describe the current context, so a missing handle never reports one.</p>
     *
     * @param previous the handle recorded for the context the caller's objects were created on, or 0 when
     *                 none has been recorded yet
     * @param current the handle the driver reports now, or 0 when it reports none
     * @return true when both handles are known and they describe different contexts
     */
    public static boolean isContextChange(long previous, long current) {
        return previous != 0L && current != 0L && previous != current;
    }

    /** Prevents instances; this class only holds the judgement. */
    private DisplayContextIdentity() {}
}
