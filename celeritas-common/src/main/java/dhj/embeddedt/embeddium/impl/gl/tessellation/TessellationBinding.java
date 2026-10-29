package dhj.embeddedt.embeddium.impl.gl.tessellation;

import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeBinding;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferTarget;

import java.util.Objects;

public record TessellationBinding(GlBufferTarget target,
                                  GlBuffer buffer,
                                  GlVertexAttributeBinding[] attributeBindings) {
    public static TessellationBinding forVertexBuffer(GlBuffer buffer, GlVertexAttributeBinding[] attributes) {
        Objects.requireNonNull(attributes);
        return new TessellationBinding(GlBufferTarget.ARRAY_BUFFER, buffer, attributes);
    }

    /**
     * Binds {@code buffer} with every attribute of {@code format} at the attribute index that format declares.
     *
     * <p>A region can hold geometry of several vertex formats in one arena, so the attribute layout has to come
     * from the format of the pass being drawn rather than from one region-wide format.</p>
     */
    public static TessellationBinding forVertexBuffer(GlBuffer buffer, GlVertexFormat format) {
        Objects.requireNonNull(format);

        var attributes = format.getAttributes();
        var bindings = new GlVertexAttributeBinding[attributes.size()];
        int index = 0;

        for (var attribute : attributes) {
            bindings[index] = new GlVertexAttributeBinding(index, attribute);
            index++;
        }

        return forVertexBuffer(buffer, bindings);
    }

    public static TessellationBinding forElementBuffer(GlBuffer buffer) {
        return new TessellationBinding(GlBufferTarget.ELEMENT_BUFFER, buffer, new GlVertexAttributeBinding[0]);
    }
}
