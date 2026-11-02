package com.gtnewhorizons.angelica.glsm.states;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Immutable client-side pixel store state covering both the pack and the unpack families of
 * {@code glPixelStore} parameters. Both families share the same fields and are distinguished only by
 * the pname family, which {@link #isPack(int)} classifies and {@link #applyDiff} maps back to the
 * matching pack or unpack pname.
 */
public record PixelStoreState(
    int alignment,
    int rowLength,
    int skipRows,
    int skipPixels,
    int imageHeight,
    int skipImages,
    boolean swapBytes,
    boolean lsbFirst
) {
    public static final PixelStoreState DEFAULT = new PixelStoreState(4, 0, 0, 0, 0, 0, false, false);

    private static final Logger LOGGER = LogManager.getLogger("GLSM");

    public boolean isDefault() {
        return this.equals(DEFAULT);
    }

    /**
     * Classifies a pixel store pname as belonging to the pack family. Anything else, including
     * unpack pnames and unknown pnames, is not a pack pname.
     */
    public static boolean isPack(int pname) {
        return switch (pname) {
            case GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
                 GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST -> true;
            default -> false;
        };
    }

    /**
     * Returns the state after applying one {@code glPixelStorei} call. Both the pack and the unpack
     * pnames of a field update the same field, so the resulting state can be reused as the delta
     * source and target of {@link #applyDiff}.
     *
     * <p>Returns {@code this} when the value is already in effect or rejected: alignment must be 1, 2,
     * 4 or 8, and every remaining integer parameter must be non-negative. A rejected value is logged
     * and leaves the tracked state untouched; the {@code glPixelStorei} call still reaches the driver,
     * which reports the matching error. Unknown pnames are ignored.
     */
    public PixelStoreState with(int pname, int param) {
        return switch (pname) {
            case GL11.GL_UNPACK_ALIGNMENT, GL11.GL_PACK_ALIGNMENT -> {
                if (param == alignment) yield this;
                if (param != 1 && param != 2 && param != 4 && param != 8) {
                    reject(pname, param, "alignment must be 1, 2, 4 or 8");
                    yield this;
                }
                yield new PixelStoreState(param, rowLength, skipRows, skipPixels, imageHeight, skipImages, swapBytes, lsbFirst);
            }
            case GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_PACK_ROW_LENGTH -> {
                if (param == rowLength) yield this;
                if (param < 0) {
                    reject(pname, param, "row length must be non-negative");
                    yield this;
                }
                yield new PixelStoreState(alignment, param, skipRows, skipPixels, imageHeight, skipImages, swapBytes, lsbFirst);
            }
            case GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_PACK_SKIP_ROWS -> {
                if (param == skipRows) yield this;
                if (param < 0) {
                    reject(pname, param, "skip rows must be non-negative");
                    yield this;
                }
                yield new PixelStoreState(alignment, rowLength, param, skipPixels, imageHeight, skipImages, swapBytes, lsbFirst);
            }
            case GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_PIXELS -> {
                if (param == skipPixels) yield this;
                if (param < 0) {
                    reject(pname, param, "skip pixels must be non-negative");
                    yield this;
                }
                yield new PixelStoreState(alignment, rowLength, skipRows, param, imageHeight, skipImages, swapBytes, lsbFirst);
            }
            case GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_PACK_IMAGE_HEIGHT -> {
                if (param == imageHeight) yield this;
                if (param < 0) {
                    reject(pname, param, "image height must be non-negative");
                    yield this;
                }
                yield new PixelStoreState(alignment, rowLength, skipRows, skipPixels, param, skipImages, swapBytes, lsbFirst);
            }
            case GL12.GL_UNPACK_SKIP_IMAGES, GL12.GL_PACK_SKIP_IMAGES -> {
                if (param == skipImages) yield this;
                if (param < 0) {
                    reject(pname, param, "skip images must be non-negative");
                    yield this;
                }
                yield new PixelStoreState(alignment, rowLength, skipRows, skipPixels, imageHeight, param, swapBytes, lsbFirst);
            }
            case GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_PACK_SWAP_BYTES -> (param != 0) == swapBytes ? this : new PixelStoreState(alignment, rowLength, skipRows, skipPixels, imageHeight, skipImages, param != 0, lsbFirst);
            case GL11.GL_UNPACK_LSB_FIRST, GL11.GL_PACK_LSB_FIRST -> (param != 0) == lsbFirst ? this : new PixelStoreState(alignment, rowLength, skipRows, skipPixels, imageHeight, skipImages, swapBytes, param != 0);
            default -> this;
        };
    }

    private static void reject(int pname, int param, String requirement) {
        LOGGER.warn("glPixelStorei(0x{}, {}) keeps the tracked pixel store state unchanged: {}", Integer.toHexString(pname), param, requirement);
    }

    /**
     * Issues only the {@code glPixelStorei} calls needed to turn {@code from} into {@code to}, using
     * the pack pnames when {@code pack} is set and the unpack pnames otherwise.
     */
    public static void applyDiff(PixelStoreState from, PixelStoreState to, boolean pack) {
        if (from == to) return;
        if (from.alignment != to.alignment) RENDER_BACKEND.pixelStorei(pack ? GL11.GL_PACK_ALIGNMENT : GL11.GL_UNPACK_ALIGNMENT, to.alignment);
        if (from.rowLength != to.rowLength) RENDER_BACKEND.pixelStorei(pack ? GL11.GL_PACK_ROW_LENGTH : GL11.GL_UNPACK_ROW_LENGTH, to.rowLength);
        if (from.skipRows != to.skipRows) RENDER_BACKEND.pixelStorei(pack ? GL11.GL_PACK_SKIP_ROWS : GL11.GL_UNPACK_SKIP_ROWS, to.skipRows);
        if (from.skipPixels != to.skipPixels) RENDER_BACKEND.pixelStorei(pack ? GL11.GL_PACK_SKIP_PIXELS : GL11.GL_UNPACK_SKIP_PIXELS, to.skipPixels);
        if (from.imageHeight != to.imageHeight) RENDER_BACKEND.pixelStorei(pack ? GL12.GL_PACK_IMAGE_HEIGHT : GL12.GL_UNPACK_IMAGE_HEIGHT, to.imageHeight);
        if (from.skipImages != to.skipImages) RENDER_BACKEND.pixelStorei(pack ? GL12.GL_PACK_SKIP_IMAGES : GL12.GL_UNPACK_SKIP_IMAGES, to.skipImages);
        if (from.swapBytes != to.swapBytes) RENDER_BACKEND.pixelStorei(pack ? GL11.GL_PACK_SWAP_BYTES : GL11.GL_UNPACK_SWAP_BYTES, to.swapBytes ? 1 : 0);
        if (from.lsbFirst != to.lsbFirst) RENDER_BACKEND.pixelStorei(pack ? GL11.GL_PACK_LSB_FIRST : GL11.GL_UNPACK_LSB_FIRST, to.lsbFirst ? 1 : 0);
    }
}
