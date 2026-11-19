package com.gtnewhorizons.angelica.rendering.particles;

import com.gtnewhorizons.angelica.glsm.ffp.ParticleQuadMesh;

/**
 * Recognises a particle quad among the vertices a particle wrote and turns it into one instance.
 *
 * <p>The decode only accepts the axis aligned billboard layout: the four corners have to match
 * {@code centre + half * corner(rotX, rotXZ, rotZ, rotYZ, rotXY)} and the texture coordinates have to
 * follow the vanilla diagonal. Everything else, including the quaternion rotated corners of a
 * particle with a non-zero {@code particleAngle}, is reported as undecodable so the caller replays
 * the original vertices instead.</p>
 *
 * <p>The five rotation arguments are the ones {@code ParticleManager.renderParticles} passes to
 * {@code Particle.renderParticle} in 1.12.2, in call order. Vanilla moved the names around in the
 * signature but still passes {@code rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY}
 * positionally, which is the order {@link ParticleQuadMesh} expects.</p>
 */
public final class ParticleQuadDecoder {

    /** Ints per vertex in {@code DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP}. */
    static final int STRIDE_INTS = 7;

    private static final int COMPONENT_X = 0;
    private static final int COMPONENT_Y = 1;
    private static final int COMPONENT_Z = 2;
    private static final int COMPONENT_U = 3;
    private static final int COMPONENT_V = 4;
    private static final int COMPONENT_COLOR = 5;
    private static final int COMPONENT_LIGHTMAP = 6;

    private ParticleQuadDecoder() {}

    /**
     * @param raw four vertices in the particle vertex format
     * @param rotationX camera billboard rotation
     * @param rotationXZ camera billboard rotation
     * @param rotationZ camera billboard rotation
     * @param rotationYZ camera billboard rotation
     * @param rotationXY camera billboard rotation
     * @param out receives the instance payload when the quad decodes
     * @return whether the vertices form one decodable particle quad
     */
    public static boolean decode(int[] raw, float rotationX, float rotationXZ, float rotationZ, float rotationYZ, float rotationXY, ParticleParams out) {
        if (raw.length != ParticleQuadMesh.VERTEX_COUNT * STRIDE_INTS) return false;

        final float sx = rotationX + rotationYZ;
        final float sy = rotationXZ;
        final float sz = rotationZ + rotationXY;
        final float denom = sx * sx + sy * sy + sz * sz;
        if (denom <= 1.0e-12f) return false;

        final float cx = (component(raw, 0, COMPONENT_X) + component(raw, 2, COMPONENT_X)) * 0.5f;
        final float cy = (component(raw, 0, COMPONENT_Y) + component(raw, 2, COMPONENT_Y)) * 0.5f;
        final float cz = (component(raw, 0, COMPONENT_Z) + component(raw, 2, COMPONENT_Z)) * 0.5f;
        final float dx = component(raw, 2, COMPONENT_X) - component(raw, 0, COMPONENT_X);
        final float dy = component(raw, 2, COMPONENT_Y) - component(raw, 0, COMPONENT_Y);
        final float dz = component(raw, 2, COMPONENT_Z) - component(raw, 0, COMPONENT_Z);
        final float half = (dx * sx + dy * sy + dz * sz) / (2.0f * denom);

        final float scale = Math.max(Math.abs(cx), Math.max(Math.abs(cy), Math.abs(cz)));
        final float tol = 1.0e-5f * (1.0f + Math.abs(half) + scale);
        for (int corner = 0; corner < ParticleQuadMesh.VERTEX_COUNT; corner++) {
            final float a = ParticleQuadMesh.cornerA(corner) * half;
            final float b = ParticleQuadMesh.cornerB(corner) * half;
            if (Math.abs(component(raw, corner, COMPONENT_X) - (cx + ParticleQuadMesh.offsetX(a, b, rotationX, rotationYZ))) > tol) return false;
            if (Math.abs(component(raw, corner, COMPONENT_Y) - (cy + ParticleQuadMesh.offsetY(b, rotationXZ))) > tol) return false;
            if (Math.abs(component(raw, corner, COMPONENT_Z) - (cz + ParticleQuadMesh.offsetZ(a, b, rotationZ, rotationXY))) > tol) return false;
        }

        if (component(raw, 0, COMPONENT_U) != component(raw, 1, COMPONENT_U)
            || component(raw, 1, COMPONENT_V) != component(raw, 2, COMPONENT_V)
            || component(raw, 2, COMPONENT_U) != component(raw, 3, COMPONENT_U)
            || component(raw, 3, COMPONENT_V) != component(raw, 0, COMPONENT_V)) {
            return false;
        }

        final int color = raw[COMPONENT_COLOR];
        final int lightmap = raw[COMPONENT_LIGHTMAP];
        for (int corner = 1; corner < ParticleQuadMesh.VERTEX_COUNT; corner++) {
            if (raw[corner * STRIDE_INTS + COMPONENT_COLOR] != color) return false;
            if (raw[corner * STRIDE_INTS + COMPONENT_LIGHTMAP] != lightmap) return false;
        }

        out.centerX = cx;
        out.centerY = cy;
        out.centerZ = cz;
        out.half = half;
        out.u0 = component(raw, 0, COMPONENT_U);
        out.v0 = component(raw, 0, COMPONENT_V);
        out.u1 = component(raw, 2, COMPONENT_U);
        out.v1 = component(raw, 2, COMPONENT_V);
        out.colorABGR = color;
        out.brightness = lightmap;
        return true;
    }

    private static float component(int[] raw, int corner, int component) {
        return Float.intBitsToFloat(raw[corner * STRIDE_INTS + component]);
    }
}
