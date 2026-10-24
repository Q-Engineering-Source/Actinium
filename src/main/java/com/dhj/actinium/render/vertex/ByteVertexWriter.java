package com.dhj.actinium.render.vertex;

import sun.misc.Unsafe;

import java.nio.ByteOrder;

/**
 * Writer for BYTE and UBYTE typed elements: every component is stored as a single byte.
 *
 * <p>Only the color encoding deals with byte order. Byte-wise stores have no inherent
 * endianness, but the buffer is also read back as native-order ints by consumers such as
 * the color multipliers, so the four color bytes must be laid out in the order that
 * makes the packed int representation identical on either platform: RGBA on little
 * endian hosts, ARGB read as bytes on big endian hosts.
 */
final class ByteVertexWriter implements VertexWriter {
    /** Single pre-built instance handed out by the writer factory. */
    static final VertexWriter INSTANCE = new ByteVertexWriter();

    private static final Unsafe UNSAFE = DirectBufferAddress.UNSAFE;
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private ByteVertexWriter() {
    }

    @Override
    public void writePosition(long target, double x, double y, double z,
                               double xOffset, double yOffset, double zOffset) {
        UNSAFE.putByte(target, (byte) (x + xOffset));
        UNSAFE.putByte(target + 1, (byte) (y + yOffset));
        UNSAFE.putByte(target + 2, (byte) (z + zOffset));
    }

    @Override
    public void writeColor(long target, int red, int green, int blue, int alpha) {
        putColorBytes(target, red, green, blue, alpha, LITTLE_ENDIAN);
    }

    /**
     * Writes the four color bytes in the platform layout described on the class.
     * Kept explicit so both orderings stay testable on any host.
     *
     * @param target native address of the element's first byte
     * @param littleEndian whether the host reads packed ints little endian
     */
    static void putColorBytes(long target, int red, int green, int blue, int alpha,
                              boolean littleEndian) {
        if (littleEndian) {
            UNSAFE.putByte(target, (byte) red);
            UNSAFE.putByte(target + 1, (byte) green);
            UNSAFE.putByte(target + 2, (byte) blue);
            UNSAFE.putByte(target + 3, (byte) alpha);
        } else {
            UNSAFE.putByte(target, (byte) alpha);
            UNSAFE.putByte(target + 1, (byte) blue);
            UNSAFE.putByte(target + 2, (byte) green);
            UNSAFE.putByte(target + 3, (byte) red);
        }
    }

    @Override
    public void writeTexCoord(long target, double u, double v) {
        UNSAFE.putByte(target, (byte) v);
        UNSAFE.putByte(target + 1, (byte) u);
    }

    @Override
    public void writeLightmap(long target, int skyLight, int blockLight) {
        UNSAFE.putByte(target, (byte) blockLight);
        UNSAFE.putByte(target + 1, (byte) skyLight);
    }

    @Override
    public void writeNormal(long target, float x, float y, float z) {
        UNSAFE.putByte(target, (byte) ((int) (x * 127) & 0xFF));
        UNSAFE.putByte(target + 1, (byte) ((int) (y * 127) & 0xFF));
        UNSAFE.putByte(target + 2, (byte) ((int) (z * 127) & 0xFF));
    }
}
