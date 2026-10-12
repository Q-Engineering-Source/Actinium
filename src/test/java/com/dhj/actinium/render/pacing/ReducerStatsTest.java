package com.dhj.actinium.render.pacing;

import com.dhj.actinium.render.pacing.ReducerStateMachine.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reducer's periodic segment says which state the reducer is holding the client in, so a frame rate
 * below the player's own limit can be attributed without opening the F3 screen.
 * <p>
 * {@link FpsReducer} itself cannot be loaded off the render thread - its static initialiser takes the
 * game's clock and the window - so the formatting is driven through {@link ReducerStats}, which is what
 * {@code FpsReducer.stateSummary} calls.
 */
class ReducerStatsTest {

    @Test
    void namesTheStateTheCapAndThePlayersOwnLimit() {
        assertEquals("reducer=[state=IDLE,enabled=true,cap=30,userLimit=120,volume=100%,benchmark=false,skipRender=false]",
            ReducerStats.line(State.IDLE, true, 30, 120, 100, false));
    }

    @Test
    void reportsAMinimizedClientAsSkippingItsWorldRender() {
        assertEquals("reducer=[state=MINIMIZED,enabled=true,cap=20,userLimit=120,volume=1%,benchmark=false,skipRender=true]",
            ReducerStats.line(State.MINIMIZED, true, 20, 120, 1, false));
    }

    @Test
    void reportsAnUncappedFocusedClientAsHoldingNothingBack() {
        assertEquals("reducer=[state=FOCUSED,enabled=true,cap=0,userLimit=260,volume=100%,benchmark=false,skipRender=false]",
            ReducerStats.line(State.FOCUSED, true, 0, 260, 100, false));
    }

    @Test
    void reportsTheBenchmarkOverrideAndADisabledReducer() {
        assertEquals("reducer=[state=FOCUSED,enabled=false,cap=0,userLimit=0,volume=100%,benchmark=true,skipRender=false]",
            ReducerStats.line(State.FOCUSED, false, 0, 0, 100, true));
    }
}
