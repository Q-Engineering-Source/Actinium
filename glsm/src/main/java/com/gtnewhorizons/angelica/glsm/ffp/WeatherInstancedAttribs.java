package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import org.lwjgl.opengl.GL11;

/**
 * The three per-instance attributes of the weather instanced draw.
 *
 * <p>Each instance is one rain or snow column, described by a 48 byte record in three {@code vec4}
 * attributes: the column offset from the camera window centre plus its bottom and top, the four
 * random values vanilla draws for the texture scroll and the snow drift, and the packed lightmap
 * with the rain scroll hash and the rain flag. The offsets and the stride below are the shared layout
 * definition of that record: the writer that fills the instance buffer aliases them, so the module
 * boundary needs no second copy of the numbers.</p>
 */
public final class WeatherInstancedAttribs {

    /** Attribute location of the {@code vec4 columnSpan}: x/z offset, bottom, top. */
    public static final int LOC_COLUMN_SPAN = 8;

    /** Attribute location of the {@code vec4 jitter}: two uniforms and two gaussians. */
    public static final int LOC_JITTER = 9;

    /** Attribute location of the {@code vec4 params}: block light, sky light, hash, rain flag. */
    public static final int LOC_PARAMS = 10;

    /** Byte offset of the {@code vec4 columnSpan} attribute inside one instance. */
    public static final int OFFSET_COLUMN_SPAN = 0;

    /** Byte offset of the {@code vec4 jitter} attribute inside one instance. */
    public static final int OFFSET_JITTER = 16;

    /** Byte offset of the {@code vec4 params} attribute inside one instance. */
    public static final int OFFSET_PARAMS = 32;

    /** Byte distance between two consecutive instances. */
    public static final int STRIDE = 48;

    private WeatherInstancedAttribs() {
    }

    /**
     * Points the three instance attributes at one instance record.
     *
     * @param base byte offset of the first instance the draw reads
     */
    public static void pointInstanceAttribs(long base) {
        GLSMVertexInput.glVertexAttribPointer(LOC_COLUMN_SPAN, 4, GL11.GL_FLOAT, false, STRIDE, base + OFFSET_COLUMN_SPAN);
        GLSMVertexInput.glVertexAttribPointer(LOC_JITTER, 4, GL11.GL_FLOAT, false, STRIDE, base + OFFSET_JITTER);
        GLSMVertexInput.glVertexAttribPointer(LOC_PARAMS, 4, GL11.GL_FLOAT, false, STRIDE, base + OFFSET_PARAMS);
    }

    /**
     * Enables the three instance attributes and advances them once per instance.
     */
    public static void enableInstanceArrays() {
        enableInstanced(LOC_COLUMN_SPAN);
        enableInstanced(LOC_JITTER);
        enableInstanced(LOC_PARAMS);
    }

    private static void enableInstanced(int location) {
        GLSMVertexInput.glEnableVertexAttribArray(location);
        GLSMVertexInput.glVertexAttribDivisor(location, 1);
    }
}
