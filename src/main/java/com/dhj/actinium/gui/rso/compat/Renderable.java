package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style renderable adapted to the 1.12.2 client.
 * Widgets extract their render state into the provided graphics context.
 */
public interface Renderable {

    void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta);
}
