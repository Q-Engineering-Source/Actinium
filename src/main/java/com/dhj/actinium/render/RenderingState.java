package com.dhj.actinium.render;

/**
 * Tracks where the render thread currently is, so features that only apply to one kind of draw can ask.
 *
 * <p>The flags are render-thread state and are restored by the mixins that set them, which keeps nested draws
 * (a GUI item rendered while the world is being drawn, for example) from being treated as the outer case.</p>
 */
public final class RenderingState {

    private static boolean renderingWorld;
    private static boolean renderingGuiItem;

    private RenderingState() {}

    public static boolean isRenderingWorld() {
        return renderingWorld;
    }

    public static void setRenderingWorld(boolean rendering) {
        renderingWorld = rendering;
    }

    public static boolean isRenderingGuiItem() {
        return renderingGuiItem;
    }

    public static void setRenderingGuiItem(boolean rendering) {
        renderingGuiItem = rendering;
    }
}
