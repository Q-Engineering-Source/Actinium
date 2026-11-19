package com.gtnewhorizons.angelica.rendering.tesr;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.nio.ByteBuffer;

/**
 * Converts vanilla vertex storage into the shared eight-int layout the batched entity and block
 * entity draws read.
 *
 * <p>Vanilla stores each vertex in the element order and stride of the active format, which is
 * usually not the shared layout: {@code POSITION_TEX_NORMAL} is six ints, {@code BLOCK} is seven
 * with the colour before the texture coordinate. Every attribute therefore has to be copied into
 * its fixed slot. Reading the vanilla bytes as if they were already eight-int vertices would shift
 * the texture coordinate into the colour slot and the normal into the lightmap slot.</p>
 *
 * <table>
 *   <caption>Per-vertex int slots</caption>
 *   <tr><th>Index</th><th>Content</th></tr>
 *   <tr><td>0-2</td><td>position, {@code Float.floatToRawIntBits}</td></tr>
 *   <tr><td>3-4</td><td>primary texture coordinate, {@code Float.floatToRawIntBits}</td></tr>
 *   <tr><td>5</td><td>colour, {@code 0xAABBGGRR}</td></tr>
 *   <tr><td>6</td><td>normal, three signed bytes {@code x | y << 8 | z << 16}</td></tr>
 *   <tr><td>7</td><td>lightmap, {@code blockLight | skyLight << 16}</td></tr>
 * </table>
 *
 * <p>Slots the format does not fill stay zero; {@code TessellatorStreamingDrawer.fillMissingAttributes}
 * fills them from GL state before the vertices are queued.</p>
 */
public final class SharedVertexLayout {

    /** Ints per vertex in the shared layout. */
    public static final int VERTEX_INTS = 8;

    private static final int POSITION = 0;
    private static final int TEXTURE = 3;
    private static final int COLOR = 5;
    private static final int NORMAL = 6;
    private static final int LIGHTMAP = 7;

    private SharedVertexLayout() {
    }

    /** @return whether the format carries the given element */
    public static boolean has(VertexFormat format, VertexFormatElement.EnumUsage usage, int index) {
        if (format == null) {
            return false;
        }
        for (int i = 0, n = format.getElementCount(); i < n; i++) {
            final VertexFormatElement element = format.getElement(i);
            if (element.getUsage() == usage && element.getIndex() == index) {
                return true;
            }
        }
        return false;
    }

    /**
     * Packs {@code vertexCount} vertices into {@code destination}.
     *
     * @param destination array of at least {@code vertexCount * VERTEX_INTS} ints
     */
    public static void pack(ByteBuffer source, VertexFormat format, int vertexCount, int[] destination) {
        if (vertexCount == 0) {
            return;
        }
        final int stride = format.getSize();
        int elementOffset = 0;
        for (int i = 0, n = format.getElementCount(); i < n; i++) {
            final VertexFormatElement element = format.getElement(i);
            final VertexFormatElement.EnumUsage usage = element.getUsage();
            final int offsetInVertex = elementOffset;
            elementOffset += element.getSize();

            if (usage == VertexFormatElement.EnumUsage.POSITION) {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    final int at = vertex * stride + offsetInVertex;
                    final int base = vertex * VERTEX_INTS + POSITION;
                    destination[base] = Float.floatToRawIntBits(source.getFloat(at));
                    destination[base + 1] = Float.floatToRawIntBits(source.getFloat(at + 4));
                    destination[base + 2] = Float.floatToRawIntBits(source.getFloat(at + 8));
                }
            } else if (usage == VertexFormatElement.EnumUsage.UV && element.getIndex() == 0) {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    final int at = vertex * stride + offsetInVertex;
                    final int base = vertex * VERTEX_INTS + TEXTURE;
                    destination[base] = Float.floatToRawIntBits(source.getFloat(at));
                    destination[base + 1] = Float.floatToRawIntBits(source.getFloat(at + 4));
                }
            } else if (usage == VertexFormatElement.EnumUsage.COLOR) {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    destination[vertex * VERTEX_INTS + COLOR] = source.getInt(vertex * stride + offsetInVertex);
                }
            } else if (usage == VertexFormatElement.EnumUsage.NORMAL) {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    final int at = vertex * stride + offsetInVertex;
                    destination[vertex * VERTEX_INTS + NORMAL] = (source.get(at) & 0xFF)
                        | (source.get(at + 1) & 0xFF) << 8
                        | (source.get(at + 2) & 0xFF) << 16;
                }
            } else if (usage == VertexFormatElement.EnumUsage.UV && element.getIndex() == 1) {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    final int at = vertex * stride + offsetInVertex;
                    destination[vertex * VERTEX_INTS + LIGHTMAP] = (source.getShort(at) & 0xFFFF)
                        | (source.getShort(at + 2) & 0xFFFF) << 16;
                }
            }
        }
    }
}
