package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.joml.Vector4f;

public final class Vec4fStack implements CowStateStack<Vec4fStack> {
    private final Vector4f value;
    private final float[][] stack;
    private final CowDepths cow = new CowDepths();

    public Vec4fStack(Vector4f value, int id) {
        this.value = value;
        cow.id = id;
        stack = new float[GLStateManager.MAX_ATTRIB_STACK_DEPTH][4];
    }

    @Override
    public CowDepths cowDepths() {
        return cow;
    }

    @Override
    public void captureSlot(int slot) {
        stack[slot][0] = value.x;
        stack[slot][1] = value.y;
        stack[slot][2] = value.z;
        stack[slot][3] = value.w;
    }

    @Override
    public void restoreSlot(int slot) {
        value.set(stack[slot][0], stack[slot][1], stack[slot][2], stack[slot][3]);
    }

    @Override
    public int stackId() {
        return cow.id;
    }
}
