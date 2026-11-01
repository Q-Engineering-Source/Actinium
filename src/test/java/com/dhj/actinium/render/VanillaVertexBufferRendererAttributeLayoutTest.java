package com.dhj.actinium.render;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the vanilla {@link VertexFormat} to generic-vertex-attribute mapping that every BufferBuilder
 * draw path resolves through ({@link VanillaBufferBuilderRenderer}, {@code BufferBuilderStreamingDrawer}
 * and {@link com.gtnewhorizons.angelica.client.rendering.DeferredDrawBatcher} all feed
 * {@link VanillaVertexBufferRenderer#attributeLocation}).
 *
 * <p>Issue #175 was a collision/drop in exactly this mapping: a UV element whose {@code index} (the
 * legacy texture unit it feeds) was 2 or 3 resolved to {@code -1}, so its attribute slot was never
 * written. Xaero's minimap and world map draw terrain with
 * {@code POSITION_TEX_TEX_TEX} — POSITION plus one UV element per unit 0..3 — and their fixed-function
 * texenv chain enables units 0/2/3 over that single vertex format, so the missing unit-2/3
 * coordinates made the chain sample one texel per 64x64 map texture and render flat colour squares.</p>
 */
class VanillaVertexBufferRendererAttributeLayoutTest {

    /** Xaero's world-map terrain format: POSITION followed by one UV element per unit 0..3. */
    private static VertexFormat perUnitUvFormat(int... textureUnits) {
        final VertexFormat format = new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F);
        for (int textureUnit : textureUnits) {
            format.addElement(uvElement(textureUnit));
        }
        return format;
    }

    private static VertexFormatElement uvElement(int textureUnit) {
        return new VertexFormatElement(
            textureUnit,
            VertexFormatElement.EnumType.FLOAT,
            VertexFormatElement.EnumUsage.UV,
            2);
    }

    private static int[] attributeLocations(VertexFormat format) {
        final int[] locations = new int[format.getElementCount()];
        for (int i = 0; i < locations.length; i++) {
            locations[i] = VanillaVertexBufferRenderer.attributeLocation(format.getElement(i));
        }
        return locations;
    }

    @Test
    void everyTextureUnitOfAMultiUvFormatOwnsASlot() {
        assertArrayEquals(
            new int[] { 0, 2, 3, 5, 6 },
            attributeLocations(perUnitUvFormat(0, 1, 2, 3)),
            "POSITION + UV units 0..3 must occupy slots 0,2,3,5,6; units 2/3 were dropped before issue #175's fix");
    }

    @Test
    void textureUnitSlotsNeverCollide() {
        final Set<Integer> seen = new HashSet<>();
        for (int location : attributeLocations(perUnitUvFormat(0, 1, 2, 3))) {
            assertTrue(location >= 0, "every supported texture unit must resolve to a real slot");
            assertTrue(seen.add(location), "slot " + location + " is shared by two elements");
        }
    }

    @Test
    void singleTextureFormatKeepsItsSlots() {
        assertArrayEquals(new int[] { 0, 2 }, attributeLocations(DefaultVertexFormats.POSITION_TEX));
    }

    @Test
    void itemAndBlockFormatsKeepTheirDocumentedSlots() {
        // POSITION, COLOR, UV0, UV1 (the lightmap), then NORMAL/PADDING in the item format.
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, attributeLocations(DefaultVertexFormats.BLOCK));
        assertArrayEquals(new int[] { 0, 1, 2, 4, -1 }, attributeLocations(DefaultVertexFormats.ITEM));
    }

    @Test
    void textureUnitsBeyondTheFixedFunctionPipelineAreReportedAsUnsupported() {
        // The FFP pipeline supplies units 0..3 only. A fifth UV element must not silently alias an
        // existing slot (the class logs the drop once per unit). Vanilla's VertexFormat adds each UV
        // slot at its element index, so the indices have to be contiguous and ascending.
        assertArrayEquals(new int[] { 0, 2, 3, 5, 6, -1 }, attributeLocations(perUnitUvFormat(0, 1, 2, 3, 4)));
    }

    @Test
    void vertexFlagsStillReportTheTextureAndLightmapUnits() {
        assertEquals(
            VertexFlags.TEXTURE_BIT | VertexFlags.BRIGHTNESS_BIT,
            VanillaVertexBufferRenderer.vertexFlags(perUnitUvFormat(0, 1, 2, 3)),
            "units 2/3 are tracked per attribute slot in VAOManager; units 0/1 keep their flags");
    }
}
