package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.CowDepths;
import com.gtnewhorizons.angelica.glsm.stacks.CowStateStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Stores eye-space clip plane equations for core profile emulation.
 */
public final class ClipPlaneState implements CowStateStack<ClipPlaneState> {
    private final float[][] eyePlanes = new float[GLStateManager.MAX_CLIP_PLANES][4];
    private final ClipPlaneState[] stack;
    private final CowDepths cow;
    private final Vector4f tempPlane;
    private final Matrix4f tempInverse;

    public ClipPlaneState() {
        this(-1);
    }

    public ClipPlaneState(int id) {
        this(false, id);
    }

    private ClipPlaneState(boolean stackEntry, int id) {
        if (stackEntry) {
            stack = null;
            cow = null;
            tempPlane = null;
            tempInverse = null;
        } else {
            stack = new ClipPlaneState[GLStateManager.STATE_SLOTS];
            for (int slot = 0; slot < stack.length; slot++) {
                stack[slot] = new ClipPlaneState(true, -1);
            }
            cow = new CowDepths();
            cow.id = id;
            tempPlane = new Vector4f();
            tempInverse = new Matrix4f();
        }
    }

    public void setPlane(int index, double a, double b, double c, double d, Matrix4f modelView) {
        final float[] dest = eyePlanes[index];
        modelView.invert(tempInverse);
        tempPlane.set((float) a, (float) b, (float) c, (float) d);
        tempInverse.transformTranspose(tempPlane);
        dest[0] = tempPlane.x;
        dest[1] = tempPlane.y;
        dest[2] = tempPlane.z;
        dest[3] = tempPlane.w;
    }

    /** Writes the 4-component eye-space plane equation into {@code buf} at its current position. */
    public void putEyePlane(int index, FloatBuffer buf) {
        final float[] p = eyePlanes[index];
        buf.put(p[0]).put(p[1]).put(p[2]).put(p[3]);
    }

    public void getEyePlane(int index, DoubleBuffer buf) {
        final float[] p = eyePlanes[index];
        final int pos = buf.position();
        buf.put(pos, p[0]).put(pos + 1, p[1]).put(pos + 2, p[2]).put(pos + 3, p[3]);
    }

    @Override
    public CowDepths cowDepths() {
        if (cow == null) throw new IllegalStateException("Cannot push/pop a clip plane stack snapshot entry");
        return cow;
    }

    @Override
    public boolean slotChanged(int slot) {
        return !Arrays.deepEquals(eyePlanes, stack[slot].eyePlanes);
    }

    @Override
    public void captureSlot(int slot) {
        stack[slot].copyFrom(this);
    }

    @Override
    public void restoreSlot(int slot) {
        copyFrom(stack[slot]);
    }

    @Override
    public boolean topSlotChanged() {
        return !cowDepths().isEmpty() && slotChanged(cowDepths().top());
    }

    @Override
    public int stackId() {
        return cow == null ? -1 : cow.id;
    }

    private void copyFrom(ClipPlaneState other) {
        for (int plane = 0; plane < eyePlanes.length; plane++) {
            System.arraycopy(other.eyePlanes[plane], 0, eyePlanes[plane], 0, eyePlanes[plane].length);
        }
    }
}
