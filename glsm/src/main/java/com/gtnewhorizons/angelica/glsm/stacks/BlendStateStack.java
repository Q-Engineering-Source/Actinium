package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaStateLayer;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import lombok.Setter;

public final class BlendStateStack extends BlendState implements CowStateStack<BlendStateStack> {
    protected final BlendState[] stack;
    private final CowDepths cow = new CowDepths(StateSet.R_BLEND);
    private boolean funcUnknown;

    @Setter private VanillaStateLayer<BlendState> vanillaLayer;

    public BlendStateStack(int id) {
        cow.id = id;
        stack = new BlendState[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
        for (int i = 0; i < GLStateManager.MAX_ATTRIB_STACK_DEPTH; i++) {
            stack[i] = new BlendState();
        }
    }

    @Override
    public CowDepths cowDepths() {
        return cow;
    }

    @Override
    public void captureSlot(int slot) {
        VanillaStateLayer.capture(vanillaLayer, stack[slot].set(this));
    }

    @Override
    public void restoreSlot(int slot) {
        final BlendState saved = stack[slot];
        if (VanillaStateLayer.restore(vanillaLayer, saved)) {
            setExceptFunc(saved);
        } else {
            set(saved);
        }
    }

    @Override
    public boolean topSlotChanged() {
        return !cow.isEmpty() && !sameAs(stack[cow.top()]);
    }

    public void setFuncUnknownState() {
        funcUnknown = true;
    }

    public void clearFuncUnknownState() {
        funcUnknown = false;
    }

    public boolean isFuncUnknown() {
        return funcUnknown;
    }

    public BlendState readEffective(BlendState out) {
        out.set(this);
        VanillaStateLayer.capture(vanillaLayer, out);
        return out;
    }

    public boolean isEnabled() {
        return GLStateManager.getBlendMode().isEnabled();
    }

    @Override
    public int stackId() {
        return cow.id;
    }
}
