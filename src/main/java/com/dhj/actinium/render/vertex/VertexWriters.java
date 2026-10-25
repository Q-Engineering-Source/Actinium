package com.dhj.actinium.render.vertex;

import net.minecraft.client.renderer.vertex.VertexFormatElement;

/**
 * Selects the pre-built {@link VertexWriter} singleton for an element type. The four writer
 * families cover the complete {@code EnumType} set: positions and texture coordinates only ever
 * switch on the type, so grouping INT with UINT, SHORT with USHORT and BYTE with UBYTE
 * reproduces the original dispatch exactly. The usage of an element never influences the
 * encoding.
 */
public final class VertexWriters {
    private VertexWriters() {
    }

    /**
     * Returns the singleton writer implementing the original encodings of the given
     * element type.
     *
     * @param type element type to dispatch on
     * @return pre-built writer singleton
     */
    public static VertexWriter forType(VertexFormatElement.EnumType type) {
        switch (type) {
            case FLOAT:
                return FloatVertexWriter.INSTANCE;
            case INT:
            case UINT:
                return IntVertexWriter.INSTANCE;
            case SHORT:
            case USHORT:
                return ShortVertexWriter.INSTANCE;
            case BYTE:
            case UBYTE:
                return ByteVertexWriter.INSTANCE;
            default:
                throw new IllegalArgumentException("Unsupported vertex element type: " + type);
        }
    }
}
