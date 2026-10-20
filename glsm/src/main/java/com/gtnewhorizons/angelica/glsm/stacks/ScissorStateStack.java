package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.ScissorState;

/** Saves scissor rectangles for nested GL_SCISSOR_BIT attribute scopes. */
public class ScissorStateStack extends ScissorState implements IStateStack<ScissorStateStack> {
    private final ScissorState[] stack = new ScissorState[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    private int pointer;

    public ScissorStateStack() {
        for (int i = 0; i < stack.length; i++) stack[i] = new ScissorState();
    }

    @Override
    public ScissorStateStack push() {
        if (pointer == stack.length) throw new IllegalStateException("Scissor stack overflow");
        stack[pointer++].set(this);
        return this;
    }

    @Override
    public ScissorStateStack pop() {
        if (pointer == 0) throw new IllegalStateException("Scissor stack underflow");
        set(stack[--pointer]);
        return this;
    }

    @Override
    public boolean isEmpty() {
        return pointer == 0;
    }
}
