package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizons.angelica.glsm.GLStateManager;

public interface CowStateStack<T> extends IStateStack<T> {
    CowDepths cowDepths();

    void captureSlot(int slot);

    void restoreSlot(int slot);

    default int stackId() {
        return cowDepths().id;
    }

    default void setStackId(int id) {
        cowDepths().id = id;
    }

    default int restoreBit() {
        return cowDepths().restoreBit;
    }

    default int restoreUnit() {
        return cowDepths().restoreUnit;
    }

    default boolean topSlotChanged() {
        return false;
    }

    @Override
    default int pushDepth() {
        return GLStateManager.getAttribDepth();
    }

    @Override
    default void beforeModify() {
        final int slot = cowDepths().claim(GLStateManager.getAttribDepth());
        if (slot >= 0) {
            captureSlot(slot);
            GLStateManager.registerModifiedState(stackId());
        }
    }

    @Override
    default boolean isEmpty() {
        return cowDepths().isEmpty();
    }

    default void drain() {
        int slot;
        while ((slot = cowDepths().pop()) >= 0) restoreSlot(slot);
    }

    @SuppressWarnings("unchecked")
    @Override
    default T push() {
        captureSlot(cowDepths().claimUnconditional());
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    @Override
    default T pop() {
        final int slot = cowDepths().pop();
        if (slot < 0) {
            throw new IllegalStateException("Stack underflow");
        }
        restoreSlot(slot);
        return (T) this;
    }
}
