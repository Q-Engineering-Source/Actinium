package com.gtnewhorizons.angelica.rendering.particles;

/**
 * One particle quad in the form an instance draw consumes: a centre, the half extent along the
 * billboarded axes, the four corner texture coordinates, the packed colour and the packed lightmap.
 *
 * <p>The same record is filled by a {@link ParticleDescriptor} without touching the vertex buffer
 * and by {@link ParticleQuadDecoder} from the vertices a particle actually wrote.</p>
 */
public final class ParticleParams {

    public float centerX;
    public float centerY;
    public float centerZ;
    public float half;
    public float u0;
    public float v0;
    public float u1;
    public float v1;
    public int colorABGR;
    public int brightness;
}
