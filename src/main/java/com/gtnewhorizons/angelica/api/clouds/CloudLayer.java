package com.gtnewhorizons.angelica.api.clouds;

import net.minecraft.util.ResourceLocation;

/**
 * Description of one cloud layer: its texture, the size of its cell grid in blocks, the scroll offsets and
 * the tint applied while drawing it.
 *
 * <p>Mods and dimensions that replace or add cloud layers publish them through {@link CloudLayerProvider}.
 * The renderer draws every returned layer without further checks, so values the geometry cannot work with
 * are rejected here instead of producing broken geometry at draw time.
 *
 * @param id stable identifier of the layer, used to keep per layer state apart and to name it in diagnostics
 * @param texture texture drawn on the layer, expected to be a cloud mask with alpha
 * @param height Y coordinate of the layer bottom in blocks
 * @param cellWidth edge length of one cloud cell in blocks, along both horizontal axes
 * @param cellHeight vertical thickness of a cloud cell in blocks, used for the fancy top face
 * @param coordinateWidth texture width in blocks, used to convert world positions into cell coordinates
 * @param offsetX scroll offset of the layer along X in cells
 * @param offsetZ scroll offset of the layer along Z in cells
 * @param red red component of the layer tint, zero to one
 * @param green green component of the layer tint, zero to one
 * @param blue blue component of the layer tint, zero to one
 * @param alpha opacity of the layer, zero to one
 */
public record CloudLayer(
    String id,
    ResourceLocation texture,
    float height,
    float cellWidth,
    float cellHeight,
    float coordinateWidth,
    double offsetX,
    double offsetZ,
    float red,
    float green,
    float blue,
    float alpha) {

    /**
     * Validates the layer description. Every rejection names the field that cannot be drawn, because a
     * silent substitution would hide a broken provider behind plausible looking clouds.
     */
    public CloudLayer {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Cloud layer id must not be empty");
        if (texture == null) throw new IllegalArgumentException("Cloud layer texture must not be null");
        if (!Float.isFinite(height)) throw new IllegalArgumentException("Cloud layer height must be finite");
        if (!Float.isFinite(cellWidth) || cellWidth <= 0
            || !Float.isFinite(cellHeight) || cellHeight <= 0
            || !Float.isFinite(coordinateWidth) || coordinateWidth <= 0) {
            throw new IllegalArgumentException("Cloud layer dimensions must be finite and positive");
        }
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetZ)) {
            throw new IllegalArgumentException("Cloud layer offsets must be finite");
        }
        if (!Float.isFinite(red) || red < 0 || !Float.isFinite(green) || green < 0
            || !Float.isFinite(blue) || blue < 0) {
            throw new IllegalArgumentException("Cloud layer colors must be finite and nonnegative");
        }
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Cloud layer alpha must be between zero and one");
        }
    }
}
