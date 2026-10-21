package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.GLStateManager;

/**
 *            __n__n__
 *     .------`-\00/-'
 *    /  ##  ## (oo)
 *   / \## __   ./
 *      |//YY \|/
 *      |||   |||
 *    Copy On Write Depths
 */

public final class CowDepths {
    public int id = -1;
    public GLContextState owner;
    public final int restoreBit;
    public final int restoreUnit;
    private final int[] slotDepth = new int[GLStateManager.MAX_ATTRIB_STACK_DEPTH];
    private int pointer;

    public CowDepths() {
        this(0, -1);
    }

    public CowDepths(int restoreBit) {
        this(restoreBit, -1);
    }

    public CowDepths(int restoreBit, int restoreUnit) {
        this.restoreBit = restoreBit;
        this.restoreUnit = restoreUnit;
    }

    /** Claims one snapshot slot before a mutation when the active scope saves this state. */
    public int claimIfNeeded(int depth, boolean scopeSavesState) {
        if (depth <= 0 || !scopeSavesState || claimedAt(depth)) {
            return -1;
        }
        return claim(depth);
    }

    public int claim(int depth) {
        if (this.pointer == this.slotDepth.length) {
            throw new IllegalStateException("COW state stack overflow: max depth " + this.slotDepth.length + " reached");
        }
        slotDepth[pointer] = depth;
        return pointer++;
    }

    public boolean claimedAt(int depth) {
        return pointer > 0 && slotDepth[pointer - 1] == depth;
    }

    public int claimUnconditional() {
        if (this.pointer == this.slotDepth.length) {
            throw new IllegalStateException("COW state stack overflow: max depth " + this.slotDepth.length + " reached");
        }
        slotDepth[pointer] = -1;
        return pointer++;
    }

    public int pop() {
        return pointer > 0 ? --pointer : -1;
    }

    public int discard(int poppedDepth) {
        if (pointer == 0) return -1;
        final int parent = poppedDepth - 1;
        if (parent > 0 && (pointer < 2 || slotDepth[pointer - 2] != parent)) {
            slotDepth[pointer - 1] = parent;
            return pointer - 1;
        }
        pointer--;
        return -1;
    }

    public int top() {
        return pointer - 1;
    }

    public boolean isEmpty() {
        return pointer == 0;
    }
}
