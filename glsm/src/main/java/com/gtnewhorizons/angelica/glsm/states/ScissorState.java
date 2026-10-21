package com.gtnewhorizons.angelica.glsm.states;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class ScissorState implements ISettableState<ScissorState> {
    public int x;
    public int y;
    public int width;
    public int height;

    public void setScissor(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setBox(int x, int y, int width, int height) {
        setScissor(x, y, width, height);
    }

    public void get(IntBuffer params) {
        final int position = params.position();
        params.put(position, x).put(position + 1, y).put(position + 2, width).put(position + 3, height);
    }

    public void get(FloatBuffer params) {
        final int position = params.position();
        params.put(position, x).put(position + 1, y).put(position + 2, width).put(position + 3, height);
    }

    public void get(DoubleBuffer params) {
        final int position = params.position();
        params.put(position, x).put(position + 1, y).put(position + 2, width).put(position + 3, height);
    }

    public void get(int[] params) {
        params[0] = x;
        params[1] = y;
        params[2] = width;
        params[3] = height;
    }

    @Override
    public ScissorState set(ScissorState state) {
        setScissor(state.x, state.y, state.width, state.height);
        return this;
    }

    @Override
    public boolean sameAs(Object state) {
        if (this == state) return true;
        if (!(state instanceof ScissorState scissorState)) return false;
        return x == scissorState.x && y == scissorState.y && width == scissorState.width && height == scissorState.height;
    }

    @Override
    public ScissorState copy() {
        return new ScissorState().set(this);
    }

}
