package com.dhj.actinium.hud.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.util.List;

/**
 * Draws the performance indicator over the game.
 *
 * <p>{@link #render(Minecraft, ScaledResolution, List)} runs on the render thread. It restores the
 * projection and model view matrices, the depth test, the depth mask and the translucent layer state
 * before returning. The pass uses program 0, texture unit 0 and straight-alpha blending. The main
 * framebuffer binding and display viewport remain installed for the vanilla frame-end presentation. The caller decides whether the indicator is visible and which lines it
 * carries, so an empty list draws nothing.
 */
public interface IndicatorHudRenderer {
    /**
     * Draws the given lines in the top left corner.
     *
     * @param minecraft client whose font, framebuffer and window sizes the pass uses
     * @param resolution GUI resolution the lines are laid out in
     * @param rows lines to draw in the order of the layout, empty for no pass at all
     */
    void render(Minecraft minecraft, ScaledResolution resolution, List<String> rows);
}
