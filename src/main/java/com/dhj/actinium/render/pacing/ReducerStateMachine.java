package com.dhj.actinium.render.pacing;

import com.gtnewhorizons.angelica.config.SystemProperties.FlybyPacing;

import java.util.function.LongSupplier;

/**
 * Chooses the frame rate limit and master volume that apply while the player is not looking at the
 * game.
 *
 * <p>The reducer exists so a backgrounded or idle client stops competing with whatever the player
 * switched to. The state is derived from window visibility, window focus and the time since the
 * last input, in that order of precedence, and each state contributes a frame rate cap and a master
 * volume. The machine itself only decides which state applies and what that state means; applying
 * the outcome is the caller's job.
 */
final class ReducerStateMachine {

    enum State {
        DISABLED(null),
        FORCED(" [reducer: forced]"),
        MINIMIZED(" [reducer: minimized]"),
        UNFOCUSED(" [reducer: unfocused]"),
        IDLE(" [reducer: idle]"),
        FOCUSED(null);

        private final String debugTag;

        State(String debugTag) {
            this.debugTag = debugTag;
        }

        String debugTag() {
            return debugTag;
        }
    }

    /**
     * The framerate slider's maximum, which both the slider and its label read as "unlimited".
     * A cap at or above it means the player asked for no limit at all.
     */
    static final int MAX_FPS_SETTING = 260;
    static final int MINIMIZED_LOOP_HZ = 20;
    static final float MIN_VOLUME_MULT = 0.01f;

    private final LongSupplier clock;
    private State state = State.FOCUSED;
    private long lastInputNanos;
    private boolean forced;
    private boolean keybindDisabled;

    ReducerStateMachine(LongSupplier clock) {
        this.clock = clock;
        this.lastInputNanos = clock.getAsLong();
    }

    /** Records player input, which clears the idle timer. */
    void onInput() {
        lastInputNanos = clock.getAsLong();
    }

    void toggleForced() {
        forced = !forced;
        onInput();
    }

    void toggleKeybindDisabled() {
        keybindDisabled = !keybindDisabled;
        onInput();
    }

    boolean keybindDisabled() {
        return keybindDisabled;
    }

    State state() {
        return state;
    }

    /**
     * Re-derives the state from the window's visibility and focus and returns whether it changed.
     * The idle timeout is only consulted while the window is visible and focused: a backgrounded
     * window is already covered by the unfocused state, and idle is the narrower case.
     */
    boolean evaluate(boolean enabled, boolean visible, boolean active, long idleTimeoutNanos) {
        final State next;
        if (!enabled || keybindDisabled) {
            next = State.DISABLED;
        } else if (forced) {
            next = State.FORCED;
        } else if (!visible) {
            next = State.MINIMIZED;
        } else if (!active) {
            next = State.UNFOCUSED;
        } else if (idleTimeoutNanos > 0 && clock.getAsLong() - lastInputNanos >= idleTimeoutNanos) {
            next = State.IDLE;
        } else {
            next = State.FOCUSED;
        }

        final boolean changed = next != state;
        state = next;
        return changed;
    }

    static int stateCap(State state, int unfocusedFps, int idleFps) {
        return switch (state) {
            case FORCED, UNFOCUSED -> uncapIfMax(unfocusedFps);
            case MINIMIZED -> MINIMIZED_LOOP_HZ;
            case IDLE -> uncapIfMax(idleFps);
            default -> 0;
        };
    }

    static int stateVolume(State state, int unfocusedVol, int minimizedVol, int idleVol) {
        return switch (state) {
            case FORCED, UNFOCUSED -> unfocusedVol;
            case MINIMIZED -> minimizedVol;
            case IDLE -> idleVol;
            default -> 100;
        };
    }

    /**
     * The cap the pacer should run at: the lower of the player's own limit and the limit the
     * current state implies. A zero on either side means "no limit", so it can never win the
     * comparison against a real limit. {@code menuCapHz} is the loading screen's own limit and is
     * supplied by the caller as zero when it does not apply.
     */
    static int mergeCap(int userLimit, int stateCap, int menuCapHz) {
        int cap = uncapIfMax(userLimit);
        if (menuCapHz > 0) {
            cap = min0(cap, menuCapHz);
        }
        return min0(cap, stateCap);
    }

    /**
     * The cap a benchmark run should use: an explicitly uncapped run ignores everything, and a
     * configured run honours the player's limit but neither the reducer nor the loading screen cap,
     * so the two runs stay comparable.
     */
    static int benchmarkCap(FlybyPacing pacing, int userLimit) {
        return pacing == FlybyPacing.UNCAPPED ? 0 : mergeCap(userLimit, 0, 0);
    }

    static float masterVolume(float user, int percent) {
        return percent >= 100 ? user : user * Math.max(MIN_VOLUME_MULT, percent / 100f);
    }

    private static int uncapIfMax(int value) {
        return value >= MAX_FPS_SETTING ? 0 : value;
    }

    private static int min0(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }
}
