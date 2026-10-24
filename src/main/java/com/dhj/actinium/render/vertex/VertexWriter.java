package com.dhj.actinium.render.vertex;

/**
 * Encodes the four immediate-mode vertex attribute writes that dominate the
 * {@code BufferBuilder} hot path. Implementations store values with absolute
 * {@code sun.misc.Unsafe} accesses at the native address of the staging buffer, skipping
 * the per-call element lookup, offset arithmetic and bounds work of the NIO views.
 *
 * <p>One singleton implementation exists per {@code VertexFormatElement.EnumType} family
 * because the original write methods switch on the element type only; the element usage
 * never alters the encoding. The staging buffer is created with native byte order, so
 * multi-byte {@code Unsafe} stores match the NIO view byte for byte and no byte swapping
 * is required anywhere in this package.
 */
public interface VertexWriter {
    /**
     * Writes a 3-component position element.
     * Mirrors the original {@code pos} encoding per element type, including the
     * translation offsets applied to every component.
     *
     * @param target native address of the element's first byte
     * @param x position x component before translation
     * @param y position y component before translation
     * @param z position z component before translation
     * @param xOffset active translation applied to x
     * @param yOffset active translation applied to y
     * @param zOffset active translation applied to z
     */
    void writePosition(long target, double x, double y, double z,
                       double xOffset, double yOffset, double zOffset);

    /**
     * Writes a 4-component color element. Disabled colors never reach this method: the
     * caller honors the {@code noColor} flag before dispatching.
     *
     * @param target native address of the element's first byte
     * @param red red component in the range the caller supplies
     */
    void writeColor(long target, int red, int green, int blue, int alpha);

    /**
     * Writes a 2-component texture coordinate element, preserving the original
     * component order per element type.
     *
     * @param target native address of the element's first byte
     */
    void writeTexCoord(long target, double u, double v);

    /**
     * Writes a 2-component lightmap element, preserving the original per-type component
     * order: unlike the texture coordinate element, the block light component comes first
     * for the integer-width types.
     *
     * @param target native address of the element's first byte
     * @param skyLight sky light coordinate
     * @param blockLight block light coordinate
     */
    void writeLightmap(long target, int skyLight, int blockLight);

    /**
     * Writes a 3-component normal element. No translation is applied, matching the
     * original {@code normal} method.
     *
     * @param target native address of the element's first byte
     */
    void writeNormal(long target, float x, float y, float z);
}
