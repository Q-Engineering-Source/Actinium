package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.CowDepths;
import com.gtnewhorizons.angelica.glsm.stacks.CowStateStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * Per-texture-unit storage for texgen mode and plane coefficients. Based on Mesa's texgen.c storage model.
 */
public final class TexGenState implements CowStateStack<TexGenState> {
    private int modeS, modeT, modeR, modeQ;

    private final float[] objectPlaneS = {1, 0, 0, 0};
    private final float[] objectPlaneT = {0, 1, 0, 0};
    private final float[] objectPlaneR = {0, 0, 0, 0};
    private final float[] objectPlaneQ = {0, 0, 0, 0};

    private final float[] eyePlaneS = {1, 0, 0, 0};
    private final float[] eyePlaneT = {0, 1, 0, 0};
    private final float[] eyePlaneR = {0, 0, 0, 0};
    private final float[] eyePlaneQ = {0, 0, 0, 0};

    private final TexGenState[] stack;
    private final CowDepths cow;
    private final Vector4f tempPlane;
    private final Matrix4f tempInverse;

    public TexGenState() {
        this(-1, -1);
    }

    public TexGenState(int id, int unitIndex) {
        this(false, id, unitIndex);
    }

    private TexGenState(boolean stackEntry, int id, int unitIndex) {
        if (stackEntry) {
            stack = null;
            cow = null;
            tempPlane = null;
            tempInverse = null;
        } else {
            stack = new TexGenState[GLStateManager.STATE_SLOTS];
            for (int slot = 0; slot < stack.length; slot++) {
                stack[slot] = new TexGenState(true, -1, -1);
            }
            cow = new CowDepths(0, unitIndex);
            cow.id = id;
            tempPlane = new Vector4f();
            tempInverse = new Matrix4f();
        }
    }

    public int getMode(int coord) {
        return switch (coord) {
            case GL11.GL_S -> modeS;
            case GL11.GL_T -> modeT;
            case GL11.GL_R -> modeR;
            case GL11.GL_Q -> modeQ;
            default -> 0;
        };
    }

    public void setMode(int coord, int mode) {
        switch (coord) {
            case GL11.GL_S -> modeS = mode;
            case GL11.GL_T -> modeT = mode;
            case GL11.GL_R -> modeR = mode;
            case GL11.GL_Q -> modeQ = mode;
        }
    }

    public float[] getObjectPlane(int coord) {
        return switch (coord) {
            case GL11.GL_S -> objectPlaneS;
            case GL11.GL_T -> objectPlaneT;
            case GL11.GL_R -> objectPlaneR;
            case GL11.GL_Q -> objectPlaneQ;
            default -> objectPlaneS;
        };
    }

    public float[] getEyePlane(int coord) {
        return switch (coord) {
            case GL11.GL_S -> eyePlaneS;
            case GL11.GL_T -> eyePlaneT;
            case GL11.GL_R -> eyePlaneR;
            case GL11.GL_Q -> eyePlaneQ;
            default -> eyePlaneS;
        };
    }

    public void setObjectPlane(int coord, float[] plane) {
        final float[] dest = getObjectPlane(coord);
        System.arraycopy(plane, 0, dest, 0, 4);
    }

    /**
     * Set eye plane coefficients. Per GL spec (Mesa texgen.c:154), the plane is transformed by the inverse of the current modelview matrix at the time of the call.
     */
    public void setEyePlane(int coord, float[] plane, Matrix4f modelView) {
        final float[] dest = getEyePlane(coord);
        modelView.invert(tempInverse);
        tempPlane.set(plane[0], plane[1], plane[2], plane[3]);
        tempInverse.transformTranspose(tempPlane);
        dest[0] = tempPlane.x;
        dest[1] = tempPlane.y;
        dest[2] = tempPlane.z;
        dest[3] = tempPlane.w;
    }

    @Override
    public CowDepths cowDepths() {
        if (cow == null) throw new IllegalStateException("Cannot push/pop a texgen stack snapshot entry");
        return cow;
    }

    @Override
    public boolean slotChanged(int slot) {
        return !sameAs(stack[slot]);
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

    private void copyFrom(TexGenState other) {
        modeS = other.modeS;
        modeT = other.modeT;
        modeR = other.modeR;
        modeQ = other.modeQ;
        System.arraycopy(other.objectPlaneS, 0, objectPlaneS, 0, 4);
        System.arraycopy(other.objectPlaneT, 0, objectPlaneT, 0, 4);
        System.arraycopy(other.objectPlaneR, 0, objectPlaneR, 0, 4);
        System.arraycopy(other.objectPlaneQ, 0, objectPlaneQ, 0, 4);
        System.arraycopy(other.eyePlaneS, 0, eyePlaneS, 0, 4);
        System.arraycopy(other.eyePlaneT, 0, eyePlaneT, 0, 4);
        System.arraycopy(other.eyePlaneR, 0, eyePlaneR, 0, 4);
        System.arraycopy(other.eyePlaneQ, 0, eyePlaneQ, 0, 4);
    }

    private boolean sameAs(TexGenState other) {
        return modeS == other.modeS && modeT == other.modeT && modeR == other.modeR && modeQ == other.modeQ
            && Arrays.equals(objectPlaneS, other.objectPlaneS)
            && Arrays.equals(objectPlaneT, other.objectPlaneT)
            && Arrays.equals(objectPlaneR, other.objectPlaneR)
            && Arrays.equals(objectPlaneQ, other.objectPlaneQ)
            && Arrays.equals(eyePlaneS, other.eyePlaneS)
            && Arrays.equals(eyePlaneT, other.eyePlaneT)
            && Arrays.equals(eyePlaneR, other.eyePlaneR)
            && Arrays.equals(eyePlaneQ, other.eyePlaneQ);
    }
}
