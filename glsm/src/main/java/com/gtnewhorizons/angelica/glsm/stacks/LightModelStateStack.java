package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.LightModelState;

public final class LightModelStateStack extends LightModelState implements CowStateStack<LightModelStateStack> {
    protected final LightModelState[] stack;
    private final CowDepths cow = new CowDepths();

    public LightModelStateStack(int id) {
        cow.id = id;
        stack = new LightModelState[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
        for (int i = 0; i < GLStateManager.MAX_ATTRIB_STACK_DEPTH; i++) {
            stack[i] = new LightModelState();
        }
    }

    @Override
    public CowDepths cowDepths() {
        return cow;
    }

    @Override
    public void captureSlot(int slot) {
        stack[slot].set(this);
    }

    @Override
    public void restoreSlot(int slot) {
        set(stack[slot]);
    }

    @Override
    public int stackId() {
        return cow.id;
    }
}
