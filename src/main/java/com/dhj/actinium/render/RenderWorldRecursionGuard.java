package com.dhj.actinium.render;

/**
 * Counts nested {@code EntityRenderer.renderWorldPass} invocations on the render thread. The Iris
 * integration in {@code EntityRendererIrisMixin} may only run on the outermost pass: a nested pass
 * would observe a swapped {@code Minecraft.world} and switch the active pipeline mid-pass,
 * corrupting the outer pass that is still rendering. BetterPortals Refitted renders portal views
 * as sequential sibling {@code renderWorld} calls (never nested) and is served by
 * {@code PipelineManager}'s per-dimension pipeline cache; this guard is the defensive net for mods
 * that truly recurse {@code renderWorldPass}. Minecraft renders on a single thread, so a plain
 * counter is sufficient.
 */
public final class RenderWorldRecursionGuard {
    private static int depth;

    private RenderWorldRecursionGuard() {
    }

    /**
     * Marks the start of one {@code renderWorldPass} invocation. Must be balanced by {@link #exit()},
     * even when the pass throws.
     */
    public static void enter() {
        depth++;
    }

    /**
     * Marks the end of one {@code renderWorldPass} invocation.
     *
     * @throws IllegalStateException when no invocation is on the stack (unbalanced enter/exit)
     */
    public static void exit() {
        if (depth == 0) {
            throw new IllegalStateException("renderWorldPass exit without a matching enter");
        }
        depth--;
    }

    /**
     * Returns whether the current pass runs nested inside another {@code renderWorldPass}. The
     * outermost pass runs at depth 1 and is not considered nested.
     */
    public static boolean isNested() {
        return depth > 1;
    }
}
