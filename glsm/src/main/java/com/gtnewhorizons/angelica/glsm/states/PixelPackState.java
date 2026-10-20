package com.gtnewhorizons.angelica.glsm.states;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Immutable client-side pixel pack state (glPixelStore PACK_* params)
 */
public record PixelPackState(
    int alignment,
    int rowLength,
    int skipRows,
    int skipPixels,
    int imageHeight,
    int skipImages,
    boolean swapBytes,
    boolean lsbFirst
) {
    public static final PixelPackState DEFAULT = new PixelPackState(4, 0, 0, 0, 0, 0, false, false);

    public boolean isDefault() {
        return this.equals(DEFAULT);
    }

    public PixelPackState with(int pname, int param) {
        return switch (pname) {
            case GL11.GL_PACK_ALIGNMENT -> (param == 1 || param == 2 || param == 4 || param == 8) ? new PixelPackState(param, rowLength, skipRows, skipPixels, imageHeight, skipImages, swapBytes, lsbFirst) : this;
            case GL11.GL_PACK_ROW_LENGTH -> param >= 0 ? new PixelPackState(alignment, param, skipRows, skipPixels, imageHeight, skipImages, swapBytes, lsbFirst) : this;
            case GL11.GL_PACK_SKIP_ROWS -> param >= 0 ? new PixelPackState(alignment, rowLength, param, skipPixels, imageHeight, skipImages, swapBytes, lsbFirst) : this;
            case GL11.GL_PACK_SKIP_PIXELS -> param >= 0 ? new PixelPackState(alignment, rowLength, skipRows, param, imageHeight, skipImages, swapBytes, lsbFirst) : this;
            case GL12.GL_PACK_IMAGE_HEIGHT -> param >= 0 ? new PixelPackState(alignment, rowLength, skipRows, skipPixels, param, skipImages, swapBytes, lsbFirst) : this;
            case GL12.GL_PACK_SKIP_IMAGES -> param >= 0 ? new PixelPackState(alignment, rowLength, skipRows, skipPixels, imageHeight, param, swapBytes, lsbFirst) : this;
            case GL11.GL_PACK_SWAP_BYTES -> new PixelPackState(alignment, rowLength, skipRows, skipPixels, imageHeight, skipImages, param != 0, lsbFirst);
            case GL11.GL_PACK_LSB_FIRST -> new PixelPackState(alignment, rowLength, skipRows, skipPixels, imageHeight, skipImages, swapBytes, param != 0);
            default -> this;
        };
    }

}
