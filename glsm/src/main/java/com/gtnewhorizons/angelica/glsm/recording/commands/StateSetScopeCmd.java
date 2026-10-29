package com.gtnewhorizons.angelica.glsm.recording.commands;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;

/** Replays an internal COW attribute scope without entering display-list recording again. */
public final class StateSetScopeCmd implements DisplayListCommand {
    private final int mask;
    private final boolean push;

    private StateSetScopeCmd(int mask, boolean push) {
        this.mask = mask;
        this.push = push;
    }

    public static StateSetScopeCmd push(StateSet stateSet) {
        if (stateSet == null) {
            throw new IllegalArgumentException("A StateSet push command requires its state set");
        }
        return new StateSetScopeCmd(stateSet.glMask, true);
    }

    public static StateSetScopeCmd pop() {
        return new StateSetScopeCmd(0, false);
    }

    @Override
    public void execute() {
        if (push) {
            GLStateManager.pushStateLive(StateSet.forMask(mask));
        } else {
            GLStateManager.popStateLive();
        }
    }
}
