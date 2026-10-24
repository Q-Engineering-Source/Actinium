package com.dhj.actinium.render.vertex;

import sun.misc.Unsafe;

/**
 * Writer for INT and UINT typed elements. Positions store their raw float bits; texture
 * coordinates and normals truncate to integer; colors are stored through the float view because
 * the original color method feeds the integer components to the float accessor unchanged.
 */
final class IntVertexWriter implements VertexWriter {
    /** Single pre-built instance handed out by the writer factory. */
    static final VertexWriter INSTANCE = new IntVertexWriter();

    private static final Unsafe UNSAFE = DirectBufferAddress.UNSAFE;

    private IntVertexWriter() {
    }

    @Override
    public void writePosition(long target, double x, double y, double z,
                               double xOffset, double yOffset, double zOffset) {
        UNSAFE.putInt(target, Float.floatToRawIntBits((float) (x + xOffset)));
        UNSAFE.putInt(target + 4, Float.floatToRawIntBits((float) (y + yOffset)));
        UNSAFE.putInt(target + 8, Float.floatToRawIntBits((float) (z + zOffset)));
    }

    @Override
    public void writeColor(long target, int red, int green, int blue, int alpha) {
        UNSAFE.putFloat(target, red);
        UNSAFE.putFloat(target + 4, green);
        UNSAFE.putFloat(target + 8, blue);
        UNSAFE.putFloat(target + 12, alpha);
    }

    @Override
    public void writeTexCoord(long target, double u, double v) {
        UNSAFE.putInt(target, (int) u);
        UNSAFE.putInt(target + 4, (int) v);
    }

    @Override
    public void writeLightmap(long target, int skyLight, int blockLight) {
        UNSAFE.putInt(target, skyLight);
        UNSAFE.putInt(target + 4, blockLight);
    }

    @Override
    public void writeNormal(long target, float x, float y, float z) {
        UNSAFE.putInt(target, (int) x);
        UNSAFE.putInt(target + 4, (int) y);
        UNSAFE.putInt(target + 8, (int) z);
    }
}
