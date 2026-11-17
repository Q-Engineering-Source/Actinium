package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleCloud;
import net.minecraft.client.particle.ParticleFirework;
import net.minecraft.client.particle.ParticleFlame;
import net.minecraft.client.particle.ParticleHeart;
import net.minecraft.client.particle.ParticleLava;
import net.minecraft.client.particle.ParticleNote;
import net.minecraft.client.particle.ParticlePortal;
import net.minecraft.client.particle.ParticleRedstone;
import net.minecraft.client.particle.ParticleSmokeNormal;
import net.minecraft.client.particle.ParticleSnowShovel;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the registered descriptors with the vertices the 1.12.2 particle bodies write for the
 * same particle and frame.
 *
 * <p>The particles are the real vanilla classes; the field the descriptors read is opened by the
 * access transformer, which also applies to the test classpath, so no stand-in is needed. The private
 * per-class base size is untouched by the descriptors' own arithmetic only if the seed frame is
 * skipped: {@code renderParticle} rewrites {@code particleScale} from that field every frame, and the
 * descriptor writes back the same value, so both sides compute from the same base.</p>
 *
 * <p>The set below covers one class per scale ramp plus the two descriptors that do not reuse the
 * plain billboard; the remaining {@code BaseScaled} registrations share that code path and differ
 * only in the field they read.</p>
 */
class ParticleDescriptorParityTest {

    private static final int[] AGES = { 0, 1, 4, 7, 11 };
    private static final float[] PARTIAL_TICKS = { 0.0F, 0.25F, 0.5F, 0.75F };

    private static final int BRIGHTNESS = 0x00F00123;

    private static final float ROT_X = 0.36F;
    private static final float ROT_XZ = 0.21F;
    private static final float ROT_Z = 0.48F;
    private static final float ROT_YZ = 0.13F;
    private static final float ROT_XY = 0.27F;

    private final ParticleParams described = new ParticleParams();
    private final ParticleParams written = new ParticleParams();
    private final ParticleRenderState state = new ParticleRenderState();

    @BeforeEach
    void setCamera() {
        Particle.interpPosX = 0.0;
        Particle.interpPosY = 0.0;
        Particle.interpPosZ = 0.0;
    }

    @Test
    void flameDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new FlameProbe(), ParticleFlame.class);
    }

    @Test
    void lavaDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new LavaProbe(), ParticleLava.class);
    }

    @Test
    void portalDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new PortalProbe(), ParticlePortal.class);
    }

    @Test
    void smokeDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new SmokeProbe(), ParticleSmokeNormal.class);
    }

    @Test
    void cloudDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new CloudProbe(), ParticleCloud.class);
    }

    @Test
    void heartDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new HeartProbe(), ParticleHeart.class);
    }

    @Test
    void noteDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new NoteProbe(), ParticleNote.class);
    }

    @Test
    void redstoneDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new RedstoneProbe(), ParticleRedstone.class);
    }

    @Test
    void snowShovelDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new SnowShovelProbe(), ParticleSnowShovel.class);
    }

    @Test
    void fireworkOverlayDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new OverlayProbe(), ParticleFirework.Overlay.class);
    }

    @Test
    void fireworkSparkDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new SparkProbe(), ParticleFirework.Spark.class);
    }

    private void assertMatchesVanilla(Particle particle, Class<?> vanillaClass) {
        final ParticleDescriptor descriptor = ParticleDescriptorRegistry.forClass(vanillaClass);
        assertTrue(descriptor != ParticleDescriptorRegistry.CAPTURE, vanillaClass.getSimpleName() + " has a descriptor");
        for (int age : AGES) {
            for (float partialTicks : PARTIAL_TICKS) {
                particle.particleAge = age;
                described.brightness = particle.getBrightnessForRender(partialTicks);
                assertTrue(descriptor.describe(particle, partialTicks, described, state));

                final BufferBuilder buffer = new BufferBuilder(64);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
                particle.renderParticle(buffer, null, partialTicks, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY);
                assertTrue(ParticleQuadDecoder.decode(buffer.getVertexState().getRawBuffer(), ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY, written));

                final String where = vanillaClass.getSimpleName() + " age " + age + " partial " + partialTicks;
                assertEquals(written.centerX, described.centerX, 1.0e-5F, where);
                assertEquals(written.centerY, described.centerY, 1.0e-5F, where);
                assertEquals(written.centerZ, described.centerZ, 1.0e-5F, where);
                assertEquals(written.half, described.half, 1.0e-5F, where);
                assertEquals(written.u0, described.u0, 1.0e-5F, where);
                assertEquals(written.v0, described.v0, 1.0e-5F, where);
                assertEquals(written.u1, described.u1, 1.0e-5F, where);
                assertEquals(written.v1, described.v1, 1.0e-5F, where);
                assertEquals(written.colorABGR, described.colorABGR, where);
                assertEquals(written.brightness, described.brightness, where);
            }
        }
    }

    /**
     * The particle bodies keep their constructors {@code protected}, so the test widens only the
     * constructor. {@code ParticleCrit} is absent because its constructor runs a tick, which needs a
     * live world; it shares the ramp path with {@code ParticleCloud} and differs only in the field it
     * reads. Everything else - the state setup, the field reads and the descriptor lookup - uses
     * the real class.
     */
    private static class FlameProbe extends ParticleFlame {
        FlameProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class LavaProbe extends ParticleLava {
        LavaProbe() {
            super(null, 4.0D, 5.0D, 6.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class PortalProbe extends ParticlePortal {
        PortalProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class SmokeProbe extends ParticleSmokeNormal {
        SmokeProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D, 1.0F);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class CloudProbe extends ParticleCloud {
        CloudProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }


    private static class HeartProbe extends ParticleHeart {
        HeartProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class NoteProbe extends ParticleNote {
        NoteProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class RedstoneProbe extends ParticleRedstone {
        RedstoneProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 1.0F, 1.0F, 1.0F);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class SnowShovelProbe extends ParticleSnowShovel {
        SnowShovelProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class OverlayProbe extends ParticleFirework.Overlay {
        OverlayProbe() {
            super(null, 4.0D, 5.0D, 6.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return BRIGHTNESS;
        }
    }

    private static class SparkProbe extends ParticleFirework.Spark {
        SparkProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D, null);
        }
    }
}
