package com.dhj.actinium.hud.render;

import com.dhj.actinium.render.GuiGlStateBoundary;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Draws the performance indicator as a translucent block in the top left corner of the screen.
 *
 * <p>The pass binds the main framebuffer and its viewport, then draws in the overlay coordinate
 * system: an orthographic projection over the GUI resolution and the model view pushed back by the
 * fixed pipeline overlay distance. Each line gets a half transparent black background and white
 * text with a drop shadow; the background of the block is as wide as its widest line.
 *
 * <p>Depth testing and depth writes are off while the block is drawn, so it stays on top of the world
 * and the vanilla HUD without touching the depth buffer. Which lines the block carries is decided by
 * the caller.
 */
public final class IndicatorHudRendererImpl implements IndicatorHudRenderer {
    private static final int ORIGIN_X = 2;
    private static final int ORIGIN_Y = 2;
    private static final int LINE_GAP = 2;
    private static final int HORIZONTAL_PADDING = 1;
    private static final int VERTICAL_PADDING = 1;
    private static final int BACKGROUND_COLOR = 0x80000000;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final double PROJECTION_NEAR = 1000.0;
    private static final double PROJECTION_FAR = 3000.0;
    private static final float MODELVIEW_Z = -2000.0F;

    @Override
    public void render(Minecraft minecraft, ScaledResolution resolution, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }

        final FontRenderer font = minecraft.fontRenderer;
        final int lineHeight = font.FONT_HEIGHT + LINE_GAP;
        final int blockWidth = blockWidth(font, rows);

        minecraft.getFramebuffer().bindFramebuffer(true);
        GLStateManager.glViewport(0, 0, minecraft.displayWidth, minecraft.displayHeight);

        GuiGlStateBoundary.beginTranslucentLayer();
        final boolean depthTest = GLSMEnable.glIsEnabled(GL11.GL_DEPTH_TEST);
        final boolean depthMask = GLSMQueries.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int previousMatrixMode = GLSMQueries.glGetInteger(GL11.GL_MATRIX_MODE);
        try {
            GLStateManager.disableDepthTest();
            GLSMDepthStencilAlpha.glDepthMask(false);

            GLStateManager.glMatrixMode(GL11.GL_PROJECTION);
            GLStateManager.glPushMatrix();
            GLStateManager.glLoadIdentity();
            GLStateManager.glOrtho(0.0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0.0,
                PROJECTION_NEAR, PROJECTION_FAR);

            GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
            GLStateManager.glPushMatrix();
            GLStateManager.glLoadIdentity();
            GLStateManager.glTranslatef(0.0F, 0.0F, MODELVIEW_Z);

            drawRows(font, rows, blockWidth, lineHeight);
        } finally {
            GLStateManager.glPopMatrix();
            GLStateManager.glMatrixMode(GL11.GL_PROJECTION);
            GLStateManager.glPopMatrix();
            GLStateManager.glMatrixMode(previousMatrixMode);
            restoreDepthTest(depthTest);
            GLSMDepthStencilAlpha.glDepthMask(depthMask);
            GuiGlStateBoundary.restoreHudBaseline();
        }
    }

    private static void drawRows(FontRenderer font, List<String> rows, int blockWidth, int lineHeight) {
        final int textX = ORIGIN_X + HORIZONTAL_PADDING;
        int lineTop = ORIGIN_Y;
        for (String row : rows) {
            Gui.drawRect(ORIGIN_X, lineTop, ORIGIN_X + blockWidth, lineTop + lineHeight, BACKGROUND_COLOR);
            font.drawStringWithShadow(row, textX, lineTop + VERTICAL_PADDING, TEXT_COLOR);
            lineTop += lineHeight;
        }
    }

    private static int blockWidth(FontRenderer font, List<String> rows) {
        int textWidth = 0;
        for (String row : rows) {
            textWidth = Math.max(textWidth, font.getStringWidth(row));
        }
        return textWidth + 2 * HORIZONTAL_PADDING;
    }

    private static void restoreDepthTest(boolean enabled) {
        if (enabled) {
            GLStateManager.enableDepthTest();
        } else {
            GLStateManager.disableDepthTest();
        }
    }
}
