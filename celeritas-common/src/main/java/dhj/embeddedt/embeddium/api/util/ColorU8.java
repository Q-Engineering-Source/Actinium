package dhj.embeddedt.embeddium.api.util;

public interface ColorU8 {
    int COMPONENT_BITS = 8;

    int COMPONENT_MASK = (1 << COMPONENT_BITS) - 1;

    /**
     * The maximum value of a color component. Used for converting normalizing floats to integers.
     */
    float COMPONENT_RANGE = (float) COMPONENT_MASK;

    /**
     * The multiplicative inverse of (value / COMPONENT_RANGE). Used for converting integers to normalized floats.
     */
    float COMPONENT_RANGE_INVERSE = 1.0f / COMPONENT_RANGE;

    /**
     * Converts a normalized float to an integer component.
     */
    static int normalizedFloatToByte(float value) {
        return (int) (value * COMPONENT_RANGE) & COMPONENT_MASK;
    }

    /**
     * Converts an integer component to a normalized floating point value.
     */
    static float byteToNormalizedFloat(int value) {
        return (float) value * COMPONENT_RANGE_INVERSE;
    }
}
