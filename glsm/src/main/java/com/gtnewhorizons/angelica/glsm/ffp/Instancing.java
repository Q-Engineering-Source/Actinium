package com.gtnewhorizons.angelica.glsm.ffp;

/**
 * Instanced draw kinds the FFP vertex shader can be specialised for.
 *
 * <p>The kind is part of the vertex shader key, so a weather draw compiles and binds its own program
 * while the ordinary immediate-mode draws keep the non-instanced one. Only kinds that have a real
 * draw path are listed; adding a value here reserves a {@link VertexKey} field value and must come
 * with the shader branch that consumes it.</p>
 */
public enum Instancing {

    /** Ordinary non-instanced draws. */
    NONE(""),

    /** One rain or snow column per instance, expanded from a four corner quad. */
    WEATHER("_weatherinstanced");

    public static final Instancing[] VALUES = values();

    /** Suffix appended to the variant name in shader dumps, empty for {@link #NONE}. */
    public final String variantSuffix;

    Instancing(String variantSuffix) {
        this.variantSuffix = variantSuffix;
    }
}
