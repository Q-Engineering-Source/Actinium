package com.dhj.actinium.render.vertex;

import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.util.List;

/**
 * Computes the offset and element-advance arrays installed on a {@code VertexFormat}. The
 * original write path recomputes the element offset through a boxed list lookup on every
 * attribute write and advances the element cursor with a modulo plus a recursion that skips
 * PADDING elements; both results are pure functions of the element sequence, so they are
 * derived once per format change and consumed as plain arrays.
 */
public final class FastVertexLayoutCalculator {
    private FastVertexLayoutCalculator() {
    }

    /**
     * Immutable pair of arrays describing a format layout.
     *
     * @param offsets byte offset of each element inside a vertex
     * @param nextIndices element-advance ring skipping PADDING elements
     */
    public record Layout(int[] offsets, int[] nextIndices) {
    }

    /**
     * Derives the layout for the given element sequence. The advance ring never loops
     * indefinitely: a format whose elements are all PADDING would make the original advance
     * recursion overflow at draw time, which is reported here instead when the format is built.
     *
     * @param elements elements in registration order, never modified
     * @return computed offsets and advance ring
     */
    public static Layout compute(List<VertexFormatElement> elements) {
        int count = elements.size();
        int[] offsets = new int[count];
        int[] nextIndices = new int[count];

        int size = 0;
        for (int i = 0; i < count; i++) {
            offsets[i] = size;
            size += elements.get(i).getSize();
        }

        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            while (elements.get(next).getUsage() == VertexFormatElement.EnumUsage.PADDING) {
                if (next == i) {
                    throw new IllegalArgumentException(
                        "Vertex format contains no element besides PADDING");
                }
                next = (next + 1) % count;
            }
            nextIndices[i] = next;
        }

        return new Layout(offsets, nextIndices);
    }
}
