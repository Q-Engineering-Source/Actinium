package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.commands.DisplayListCommand;
import com.gtnewhorizons.angelica.glsm.recording.commands.MultMatrixCmd;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Helper class for tracking and collapsing matrix transforms during optimization.
 * Implements the transform accumulation and emission strategy:
 * <ul>
 *   <li>accumulated: total relative transform (what GL matrix should be)</li>
 *   <li>lastEmitted: what we've emitted (what GL matrix is)</li>
 *   <li>stack: saved accumulated values at Push points</li>
 * </ul>
 */
class TransformOptimizer {
    private final Matrix4f accumulated = new Matrix4f();
    private final Matrix4f lastEmitted = new Matrix4f();
    private final Deque<Matrix4f> stack = new ArrayDeque<>();
    private final int listId;  // For logging
    private boolean absoluteMatrixFlag = false;  // True after LoadMatrix, cleared by LoadIdentity emit

    TransformOptimizer(int listId) {
        this.listId = listId;
        accumulated.identity();
        lastEmitted.identity();
    }

    Matrix4f getAccumulated() {
        return accumulated;
    }

    void loadIdentity() {
        accumulated.identity();
    }

    void pushTransform() {
        stack.push(new Matrix4f(accumulated));
    }

    void popTransform() {
        if (!stack.isEmpty()) {
            final Matrix4f popped = stack.pop();
            accumulated.set(popped);
            // After Pop, GL state is restored to the pushed value so lastEmitted should also reflect this
            lastEmitted.set(accumulated);
        } else {
            GLStateManager.LOGGER.warn("[TransformOptimizer] list={} Pop with empty stack - resetting to identity", listId);
            accumulated.identity();
            lastEmitted.identity();
        }
    }

    boolean isIdentity() {
        return DisplayListManager.isIdentity(accumulated);
    }

    boolean hasPendingTransform() {
        return !accumulated.equals(lastEmitted);
    }

    /**
     * Updates lastEmitted to match accumulated after emission.
     */
    void emitPendingTransform(List<DisplayListCommand> output) {
        if (!accumulated.equals(lastEmitted)) {
            emitTransformTo(output, accumulated);
        }
    }

    /**
     * Emit a MultMatrix command to make GL state match a target transform.
     * Used when a draw expects a specific transform that may differ from accumulated.
     * Updates lastEmitted to match target after emission.
     */
    void emitTransformTo(List<DisplayListCommand> output, Matrix4f target) {
        if (target.equals(lastEmitted)) {
            return;
        }

        // delta = inverse(lastEmitted) * target
        // But for simplicity, if lastEmitted is identity, just emit target
        if (DisplayListManager.isIdentity(lastEmitted)) {
            output.add(MultMatrixCmd.create(target));
        } else {
            final Matrix4f delta = new Matrix4f(lastEmitted).invert().mul(target);
            output.add(MultMatrixCmd.create(delta));
        }
        lastEmitted.set(target);
    }

    boolean needsTransformForDraw(Matrix4f drawTransform) {
        return !drawTransform.equals(lastEmitted);
    }

    /**
     * After LoadMatrix the GL matrix is absolute, so subsequent LoadIdentity commands must be
     * emitted to actually reset GL to identity.
     */
    void markAbsoluteMatrix() {
        absoluteMatrixFlag = true;
    }

    /**
     * Used to determine if LoadIdentity needs to emit.
     * @return true if LoadMatrix was called and not yet reset by LoadIdentity
     */
    boolean checkAndClearAbsoluteMatrix() {
        boolean was = absoluteMatrixFlag;
        absoluteMatrixFlag = false;
        return was;
    }
}
