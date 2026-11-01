package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.glsm.states.TextureBinding;

public final class TextureBindingStack extends TextureBinding implements CowStateStack<TextureBindingStack> {
    protected final TextureBinding[] stack;
    private final CowDepths cow;

    public TextureBindingStack(int id, int unit) {
        cow = new CowDepths(StateSet.R_TEXTURE, unit);
        cow.id = id;
        stack = new TextureBinding[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
        for (int i = 0; i < GLStateManager.MAX_ATTRIB_STACK_DEPTH; i++) {
            stack[i] = new TextureBinding();
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
    public boolean topSlotChanged() {
        return !cow.isEmpty() && !sameAs(stack[cow.top()]);
    }

    @Override
    public int stackId() {
        return cow.id;
    }
}
