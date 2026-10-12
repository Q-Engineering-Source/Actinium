package com.dhj.actinium.render.pacing;

import com.dhj.actinium.render.pacing.ReducerStateMachine.State;

/**
 * Formats the reducer's periodic segment, which is what attributes a frame rate below the player's own
 * limit to the reducer instead of to the renderer.
 *
 * <p>The formatting lives here rather than in {@link FpsReducer} because that class reaches for the window
 * and the game's own clock as it loads, neither of which exists off the render thread; the state and the
 * numbers are the caller's, and this turns them into the one line the report appends.</p>
 */
final class ReducerStats {

    private ReducerStats() {}

    /**
     * @param state the state the reducer last derived
     * @param enabled whether the reducer is enabled at all
     * @param cap the frame rate limit the state asks for, or zero for none
     * @param userLimit the frame rate limit the player chose
     * @param volumePercent the master volume the reducer applied, as a percentage
     * @param benchmark whether a benchmark run has taken over the frame rate policy
     */
    static String line(State state, boolean enabled, int cap, int userLimit, int volumePercent, boolean benchmark) {
        return "reducer=[state=" + state
            + ",enabled=" + enabled
            + ",cap=" + cap
            + ",userLimit=" + userLimit
            + ",volume=" + volumePercent + "%"
            + ",benchmark=" + benchmark
            + ",skipRender=" + (state == State.MINIMIZED)
            + ']';
    }
}
