package com.gtnewhorizons.angelica.glsm.states;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Scissor rectangle tracked independently of its enable flag for attribute scopes. */
public class ScissorState implements ISettableState<ScissorState> {
    public int x;
    public int y;
    public int width;
    public int height;

    public void setBox(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void get(IntBuffer params) {
        final int pos = params.position();
        params.put(pos, x).put(pos + 1, y).put(pos + 2, width).put(pos + 3, height);
    }

    public void get(FloatBuffer params) {
        final int pos = params.position();
        params.put(pos, x).put(pos + 1, y).put(pos + 2, width).put(pos + 3, height);
    }

    public void get(DoubleBuffer params) {
        final int pos = params.position();
        params.put(pos, x).put(pos + 1, y).put(pos + 2, width).put(pos + 3, height);
    }

    @Override
    public ScissorState set(ScissorState state) {
        setBox(state.x, state.y, state.width, state.height);
        return this;
    }

    @Override
    public boolean sameAs(Object state) {
        return state instanceof ScissorState other
            && x == other.x && y == other.y && width == other.width && height == other.height;
    }

    @Override
    public ScissorState copy() {
        return new ScissorState().set(this);
    }
}
