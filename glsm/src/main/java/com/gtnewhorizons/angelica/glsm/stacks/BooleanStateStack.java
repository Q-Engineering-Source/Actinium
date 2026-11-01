package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaBooleanLayer;
import com.gtnewhorizons.angelica.glsm.states.BooleanState;
import lombok.Setter;

public class BooleanStateStack extends BooleanState implements CowStateStack<BooleanStateStack> {
    protected final boolean[] stack;
    private final CowDepths cow = new CowDepths();

    @Setter private VanillaBooleanLayer vanillaLayer;

    public BooleanStateStack(int glCap) {
        this(glCap, false, false);
    }

    public BooleanStateStack(int glCap, boolean initialState) {
        this(glCap, initialState, false);
    }

    public BooleanStateStack(int glCap, boolean initialState, boolean ffpStateOnly) {
        super(glCap, ffpStateOnly);
        this.enabled = initialState;
        stack = new boolean[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    }

    @Override
    public CowDepths cowDepths() {
        return cow;
    }

    @Override
    public void captureSlot(int slot) {
        stack[slot] = vanillaValue();
    }

    @Override
    public void restoreSlot(int slot) {
        restore(stack[slot]);
    }

    @Override
    public int stackId() {
        return cow.id;
    }

    public boolean isEffectivelyEnabled() {
        return vanillaLayer != null && vanillaLayer.isOverrideHeld() ? vanillaLayer.getVanilla() : enabled;
    }

    private boolean vanillaValue() {
        return isEffectivelyEnabled();
    }

    private void restore(boolean value) {
        if (vanillaLayer != null && vanillaLayer.isOverrideHeld()) {
            vanillaLayer.setVanilla(value);
        } else {
            setEnabledDirect(value);
        }
    }

    @Override
    public int getDepth() {
        return GLStateManager.getAttribDepth();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (GLStateManager.getAttribDepth() > 0
            && (stateUnknown || !GLStateManager.isCachingEnabled() || enabled != vanillaValue())) {
            beforeModify();
        }
        super.setEnabled(enabled);
    }

    private void setEnabledDirect(boolean enabled) {
        super.setEnabled(enabled);
    }
}
