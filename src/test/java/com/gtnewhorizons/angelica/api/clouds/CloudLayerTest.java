package com.gtnewhorizons.angelica.api.clouds;

import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudLayerTest {

    @Test
    void rejectsAnEmptyId() {
        final Draft draft = new Draft();
        draft.id = null;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.id = "   ";
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void rejectsAMissingTexture() {
        final Draft draft = new Draft();
        draft.texture = null;
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void rejectsNonPositiveDimensions() {
        final Draft draft = new Draft();
        draft.cellWidth = 0.0F;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.cellWidth = 12.0F;
        draft.cellHeight = -4.0F;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.cellHeight = 4.0F;
        draft.coordinateWidth = Float.NaN;
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void rejectsNonFiniteHeightAndOffsets() {
        final Draft draft = new Draft();
        draft.height = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.height = 128.0F;
        draft.offsetX = Double.NaN;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.offsetX = 0.0;
        draft.offsetZ = Double.NEGATIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void rejectsNegativeOrNonFiniteColours() {
        final Draft draft = new Draft();
        draft.red = -0.25F;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.red = 1.0F;
        draft.green = Float.NaN;
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void rejectsAlphaOutsideTheUnitRange() {
        final Draft draft = new Draft();
        draft.alpha = -0.1F;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.alpha = 1.5F;
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void acceptsTheBoundaryValuesTheGeometryCanDraw() {
        final Draft draft = new Draft();
        draft.alpha = 0.0F;
        draft.height = -64.0F;
        assertDoesNotThrow(draft::build);
        draft.alpha = 1.0F;
        assertDoesNotThrow(draft::build);
    }

    /** Mutable valid layer description, so each test can break exactly one field. */
    private static final class Draft {
        private String id = "test:layer";
        private ResourceLocation texture = new ResourceLocation("minecraft", "textures/environment/clouds.png");
        private float height = 128.0F;
        private float cellWidth = 12.0F;
        private float cellHeight = 4.0F;
        private float coordinateWidth = 12.0F;
        private double offsetX = 0.0;
        private double offsetZ = 0.33;
        private float red = 1.0F;
        private float green = 1.0F;
        private float blue = 1.0F;
        private float alpha = 0.8F;

        private CloudLayer build() {
            return new CloudLayer(id, texture, height, cellWidth, cellHeight, coordinateWidth, offsetX, offsetZ, red, green, blue, alpha);
        }
    }
}
