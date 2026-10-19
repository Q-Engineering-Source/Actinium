package com.dhj.actinium.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/** Builds bounding-box outlines without relying on the caller's color interpolation mode. */
public final class BoundingBoxOutline {
    private BoundingBoxOutline() {
    }

    /** Begins and fills the position/color buffer; the caller owns drawing and GL state. */
    public static void build(BufferBuilder buffer, double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ,
                             float red, float green, float blue, float alpha) {
        // Vanilla joins its edges with alpha-zero strip vertices. Smooth interpolation makes
        // those connectors visible, so submit only the twelve actual edges as independent lines.
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        edge(buffer, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        edge(buffer, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha);
        edge(buffer, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        edge(buffer, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha);

        edge(buffer, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        edge(buffer, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
        edge(buffer, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        edge(buffer, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);

        edge(buffer, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        edge(buffer, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        edge(buffer, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        edge(buffer, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
    }

    private static void edge(BufferBuilder buffer, double startX, double startY, double startZ,
                             double endX, double endY, double endZ,
                             float red, float green, float blue, float alpha) {
        buffer.pos(startX, startY, startZ).color(red, green, blue, alpha).endVertex();
        buffer.pos(endX, endY, endZ).color(red, green, blue, alpha).endVertex();
    }
}
