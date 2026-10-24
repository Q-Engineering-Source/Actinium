package com.dhj.actinium.gui.rso.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * 1.12.2 adaptation of the newer Minecraft {@code GuiGraphicsExtractor}
 * used by Reese's Sodium Options. Maps every draw call onto the vanilla
 * 1.12.2 immediate-mode GUI primitives without changing any color, size or
 * layout constant carried by the caller.
 */
public final class GuiGraphicsExtractor {

    public void fill(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y2, color);
    }

    /** Fills a rectangle with the given ARGB color (pipeline overload retained for source compatibility). */
    public void fill(int pipeline, int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y2, color);
    }

    public void text(Font font, String text, int x, int y, int color) {
        this.text(font, text, x, y, color, false);
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        this.drawText(font, text, x, y, color, shadow);
    }

    public void text(Font font, Component text, int x, int y, int color) {
        this.text(font, text, x, y, color, false);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        Style rgbStyle = text.rgbStyle();
        if (rgbStyle != null && rgbStyle.rgbColor() != null) {
            // The 1.12.2 renderer cannot express arbitrary RGB inside legacy
            // formatting codes; pass the color through the draw color instead.
            this.drawText(font, text.getString(), x, y, rgbStyle.rgbColor(), shadow);
        } else {
            this.drawText(font, text.getFormattedString(), x, y, color, shadow);
        }
    }

    public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
        this.text(font, text, x, y, color, false);
    }

    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        Style style = text.style();
        if (style != null && style.rgbColor() != null) {
            this.drawText(font, text.getString(), x, y, style.rgbColor(), shadow);
        } else {
            this.drawText(font, style != null && style != Style.EMPTY
                    ? style.legacyPrefix() + style.legacyFlags() + text.getString()
                    : text.getString(), x, y, color, shadow);
        }
    }

    public void centeredText(Font font, Component text, int x, int y, int color) {
        int width = font.width(text);
        this.text(font, text, x - width / 2, y, color);
    }

    public void blit(ResourceLocation texture, int x, int y, float u, float v, int width, int height,
                     int textureWidth, int textureHeight) {
        this.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight, 0xFFFFFFFF);
    }

    public void blit(ResourceLocation texture, int x, int y, float u, float v, int width, int height,
                     int textureWidth, int textureHeight, int color) {
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        float alpha = (color >> 24 & 255) / 255.0F;
        GlStateManager.color(red, green, blue, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        Gui.drawModalRectWithCustomSizedTexture(x, y, u, v, width, height, textureWidth, textureHeight);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (!blendWasEnabled) {
            GlStateManager.disableBlend();
        }
    }

    /** Enables a scissor box in GUI coordinates. */
    public void enableScissor(int x, int y, int width, int height) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc);
        int scale = resolution.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, mc.displayHeight - (y + height) * scale, width * scale, height * scale);
    }

    public void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** Requests a cursor shape; a no-op on 1.12.2 which has no cursor API. */
    public void requestCursor(int cursorType) {
    }

    private void drawText(Font font, String text, int x, int y, int color, boolean shadow) {
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        font.getDelegate().drawString(text, x, y, color, shadow);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (!blendWasEnabled) {
            GlStateManager.disableBlend();
        }
    }
}
