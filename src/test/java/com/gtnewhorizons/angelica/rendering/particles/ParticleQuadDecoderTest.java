package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the vertices 1.12.2's own {@code Particle.renderParticle} writes, so the five rotation
 * arguments and the per-vertex colour and lightmap checks are pinned against vanilla arithmetic
 * instead of against a copy of it.
 */
class ParticleQuadDecoderTest {

    private static final int VERTEX_INTS = 7;
    private static final float EPSILON = 1.0e-6F;
    static final int BRIGHTNESS = 0x00F00123;

    /** Rotation arguments in the order {@code ParticleManager.renderParticles} passes them. */
    private record Rotation(float x, float xz, float z, float yz, float xy) {}

    private static final Rotation ROTATION = new Rotation(0.36F, 0.21F, 0.48F, 0.13F, 0.27F);
    private static final Rotation OTHER_ROTATION = new Rotation(0.5F, 0.1F, 0.2F, 0.9F, 0.7F);

    private final ParticleParams params = new ParticleParams();

    @BeforeEach
    void resetCamera() {
        Particle.interpPosX = 0.0;
        Particle.interpPosY = 0.0;
        Particle.interpPosZ = 0.0;
        Particle.cameraViewDir = new Vec3d(0.0, 0.0, 1.0);
    }

    @AfterEach
    void clearCamera() {
        Particle.cameraViewDir = null;
    }

    @Test
    void decodesTheQuadVanillaWritesForTheFiveRotationArguments() {
        final Probe probe = new Probe();
        probe.setScale(1.6F);
        final int[] raw = probe.capture(0.5F, ROTATION);

        assertTrue(ParticleQuadDecoder.decode(raw, ROTATION.x(), ROTATION.xz(), ROTATION.z(), ROTATION.yz(), ROTATION.xy(), params));

        assertEquals(1.5D, params.centerX, EPSILON);
        assertEquals(2.5D, params.centerY, EPSILON);
        assertEquals(3.5D, params.centerZ, EPSILON);
        assertEquals(0.1F * 1.6F, params.half, EPSILON);
        assertEquals(ParticleQuads.packColor(0.25F, 0.5F, 0.75F, 1.0F), params.colorABGR);
        assertEquals(ParticleQuadDecoderTest.BRIGHTNESS, params.brightness);
    }

    @Test
    void rejectsAQuadBuiltFromADifferentRotationBasis() {
        final int[] raw = new Probe().capture(0.5F, ROTATION);

        assertFalse(ParticleQuadDecoder.decode(raw, OTHER_ROTATION.x(), OTHER_ROTATION.xz(), OTHER_ROTATION.z(),
            OTHER_ROTATION.yz(), OTHER_ROTATION.xy(), params));
    }

    @Test
    void rejectsAQuadWhoseCornersDoNotFollowTheUvPattern() {
        final Probe probe = new Probe();
        final int[] raw = probe.capture(0.5F, ROTATION);
        // Corner 2 must carry the min/min texture coordinate; a mismatch is not a vanilla quad.
        raw[2 * VERTEX_INTS + 3] = Float.floatToRawIntBits(0.75F);

        assertFalse(ParticleQuadDecoder.decode(raw, ROTATION.x(), ROTATION.xz(), ROTATION.z(), ROTATION.yz(), ROTATION.xy(), params));
    }

    @Test
    void rejectsAQuadWhoseVertexColorsDisagree() {
        final int[] raw = new Probe().capture(0.5F, ROTATION);
        raw[3 * VERTEX_INTS + 5] = 0xFF00FF00;

        assertFalse(ParticleQuadDecoder.decode(raw, ROTATION.x(), ROTATION.xz(), ROTATION.z(), ROTATION.yz(), ROTATION.xy(), params));
    }

    @Test
    void rejectsAQuadWhoseVertexLightmapsDisagree() {
        final int[] raw = new Probe().capture(0.5F, ROTATION);
        raw[1 * VERTEX_INTS + 6] = 0x00F00000;

        assertFalse(ParticleQuadDecoder.decode(raw, ROTATION.x(), ROTATION.xz(), ROTATION.z(), ROTATION.yz(), ROTATION.xy(), params));
    }

    @Test
    void rejectsTheQuaternionRotatedCornersOfASpinningParticle() {
        final Probe probe = new Probe();
        probe.setParticleAngle(0.8F);
        final int[] raw = probe.capture(0.5F, ROTATION);

        assertFalse(ParticleQuadDecoder.decode(raw, ROTATION.x(), ROTATION.xz(), ROTATION.z(), ROTATION.yz(), ROTATION.xy(), params),
            "a particleAngle rotation leaves the axis aligned layout and has to be replayed as vertices");
    }

    @Test
    void rejectsAQuadWithTheWrongVertexCount() {
        final Probe probe = new Probe();
        final int[] raw = probe.capture(0.5F, ROTATION);
        final int[] three = new int[VERTEX_INTS * 3];
        System.arraycopy(raw, 0, three, 0, three.length);

        assertFalse(ParticleQuadDecoder.decode(three, ROTATION.x(), ROTATION.xz(), ROTATION.z(), ROTATION.yz(), ROTATION.xy(), params));
    }

    /** Writes vertices through the vanilla {@code Particle.renderParticle} body. */
    static final class Probe extends Particle {

        Probe() {
            super(null, 1.5D, 2.5D, 3.5D);
            this.setRBGColorF(0.25F, 0.5F, 0.75F);
            this.particleAge = 4;
            this.particleMaxAge = 20;
        }

        void setScale(float scale) {
            this.particleScale = scale;
        }

        void setParticleAngle(float angle) {
            this.particleAngle = angle;
            this.prevParticleAngle = angle;
        }

        int[] capture(float partialTicks, Rotation rotation) {
            final BufferBuilder buffer = new BufferBuilder(64);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
            renderParticle(buffer, null, partialTicks, rotation.x(), rotation.xz(), rotation.z(), rotation.yz(), rotation.xy());
            return buffer.getVertexState().getRawBuffer();
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }
}
