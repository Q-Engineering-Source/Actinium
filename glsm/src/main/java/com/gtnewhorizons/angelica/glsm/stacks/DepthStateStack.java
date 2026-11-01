package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaStateLayer;
import com.gtnewhorizons.angelica.glsm.states.DepthState;
import lombok.Setter;

public final class DepthStateStack extends DepthState implements CowStateStack<DepthStateStack> {
    protected final DepthState[] stack;
    private final CowDepths cow = new CowDepths(StateSet.R_DEPTH);

    @Setter private VanillaStateLayer<DepthState> vanillaLayer;
    private final DepthState effective = new DepthState();

    public DepthStateStack(int id) {
        cow.id = id;
        stack = new DepthState[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
        for (int i = 0; i < GLStateManager.MAX_ATTRIB_STACK_DEPTH; i++) {
            stack[i] = new DepthState();
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
        final DepthState saved = stack[slot];
        if (VanillaStateLayer.restore(vanillaLayer, saved)) {
            setExceptMask(saved);
        } else {
            set(saved);
        }
    }

    @Override
    public boolean topSlotChanged() {
        return !cow.isEmpty() && !sameAs(stack[cow.top()]);
    }

    public boolean isEffectiveMaskEnabled() {
        if (!VanillaStateLayer.isHeld(vanillaLayer)) {
            return isMaskEnabled();
        }
        vanillaLayer.readVanilla(effective);
        return effective.isMaskEnabled();
    }

    @Override
    public int stackId() {
        return cow.id;
    }
}
