package com.dhj.actinium.render.vertex;

/**
 * Read side of the pre-computed vertex format layout installed on every {@code VertexFormat}.
 * Element offset and element-advance information are properties of the format, not of the
 * element: the vanilla format constants share a handful of {@code VertexFormatElement} instances
 * across many formats, so per-element state would be overwritten by whichever format registered
 * the element last. Keeping the state in arrays owned by the format makes the hot path a plain
 * array load instead of a boxed {@code List.get}.
 */
public interface FastVertexLayout {
    /**
     * Returns the byte offset of every element inside a vertex, indexed by element
     * position within the format.
     *
     * @return offsets array parallel to the format's element list
     */
    int[] actinium$offsets();

    /**
     * Returns the element-advance ring, indexed by element position: the value is the
     * position of the next element with a non-PADDING usage, which is where the original
     * private advance method stops. Positions whose element already has a non-PADDING
     * usage and positions holding a PADDING element both advance to the same next
     * non-PADDING element, so advancing stays correct even right after a format switch.
     *
     * @return ring array parallel to the format's element list
     */
    int[] actinium$nextIndices();
}
