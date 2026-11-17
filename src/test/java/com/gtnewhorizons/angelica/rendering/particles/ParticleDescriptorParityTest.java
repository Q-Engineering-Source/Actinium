package com.gtnewhorizons.angelica.rendering.particles;

import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticle;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleFireworkSpark;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleFlame;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleLava;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticlePortal;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleSmokeNormal;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFirework;
import net.minecraft.client.particle.ParticleFlame;
import net.minecraft.client.particle.ParticleLava;
import net.minecraft.client.particle.ParticlePortal;
import net.minecraft.client.particle.ParticleSmokeNormal;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the descriptors with the vertices the 1.12.2 particle bodies write for the same particle
 * and frame.
 *
 * <p>Each probe is the real particle class, standing in for the accessors the mixins add at runtime:
 * its accessor implementations read the inherited fields, and the private base size field - which a
 * subclass cannot read - ends up equal to {@code particleScale} at the end of every one of those
 * constructors, so the value captured by the probe field initialiser is that field's value.</p>
 *
 * <p>The probe set covers one class per scale ramp plus the two descriptors that do not reuse the
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
    void fireworkOverlayDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new OverlayProbe(), ParticleFirework.Overlay.class);
    }

    @Test
    void fireworkSparkDescriptorMatchesVanillaAcrossFrames() {
        assertMatchesVanilla(new SparkProbe(), ParticleFirework.Spark.class);
    }

    private void assertMatchesVanilla(Probe probe, Class<?> vanillaClass) {
        final ParticleDescriptor descriptor = ParticleDescriptorRegistry.forClass(vanillaClass);
        assertTrue(descriptor != ParticleDescriptorRegistry.CAPTURE, vanillaClass.getSimpleName() + " has a descriptor");
        final Particle particle = (Particle) probe;
        for (int age : AGES) {
            for (float partialTicks : PARTIAL_TICKS) {
                probe.actinium$setParticleAge(age);
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

    /** A probe, plus the age setter every probe shares. */
    interface Probe {
        void actinium$setParticleAge(int age);
    }

    static final class FlameProbe extends ParticleFlame implements Probe, AccessorParticle, AccessorParticleFlame {

        private final float base = this.particleScale;

        FlameProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override public float actinium$flameScale() { return base; }
        @Override public int getBrightnessForRender(float partialTick) { return BRIGHTNESS; }
        @Override public void actinium$setParticleAge(int age) { this.particleAge = age; }
        @Override public double actinium$getPrevPosX() { return this.prevPosX; }
        @Override public double actinium$getPrevPosY() { return this.prevPosY; }
        @Override public double actinium$getPrevPosZ() { return this.prevPosZ; }
        @Override public double actinium$getPosX() { return this.posX; }
        @Override public double actinium$getPosY() { return this.posY; }
        @Override public double actinium$getPosZ() { return this.posZ; }
        @Override public int actinium$getParticleAge() { return this.particleAge; }
        @Override public int actinium$getParticleMaxAge() { return this.particleMaxAge; }
        @Override public float actinium$getParticleScale() { return this.particleScale; }
        @Override public void actinium$setParticleScale(float scale) { this.particleScale = scale; }
        @Override public float actinium$getParticleAlpha() { return this.particleAlpha; }
        @Override public void actinium$setParticleAlpha(float alpha) { this.particleAlpha = alpha; }
        @Override public TextureAtlasSprite actinium$getParticleTexture() { return this.particleTexture; }
        @Override public int actinium$getParticleTextureIndexX() { return this.particleTextureIndexX; }
        @Override public int actinium$getParticleTextureIndexY() { return this.particleTextureIndexY; }
        @Override public float actinium$getParticleTextureJitterX() { return this.particleTextureJitterX; }
        @Override public float actinium$getParticleTextureJitterY() { return this.particleTextureJitterY; }
    }

    static final class LavaProbe extends ParticleLava implements Probe, AccessorParticle, AccessorParticleLava {

        private final float base = this.particleScale;

        LavaProbe() {
            super(null, 4.0D, 5.0D, 6.0D);
        }

        @Override public float actinium$lavaParticleScale() { return base; }
        @Override public int getBrightnessForRender(float partialTick) { return BRIGHTNESS; }
        @Override public void actinium$setParticleAge(int age) { this.particleAge = age; }
        @Override public double actinium$getPrevPosX() { return this.prevPosX; }
        @Override public double actinium$getPrevPosY() { return this.prevPosY; }
        @Override public double actinium$getPrevPosZ() { return this.prevPosZ; }
        @Override public double actinium$getPosX() { return this.posX; }
        @Override public double actinium$getPosY() { return this.posY; }
        @Override public double actinium$getPosZ() { return this.posZ; }
        @Override public int actinium$getParticleAge() { return this.particleAge; }
        @Override public int actinium$getParticleMaxAge() { return this.particleMaxAge; }
        @Override public float actinium$getParticleScale() { return this.particleScale; }
        @Override public void actinium$setParticleScale(float scale) { this.particleScale = scale; }
        @Override public float actinium$getParticleAlpha() { return this.particleAlpha; }
        @Override public void actinium$setParticleAlpha(float alpha) { this.particleAlpha = alpha; }
        @Override public TextureAtlasSprite actinium$getParticleTexture() { return this.particleTexture; }
        @Override public int actinium$getParticleTextureIndexX() { return this.particleTextureIndexX; }
        @Override public int actinium$getParticleTextureIndexY() { return this.particleTextureIndexY; }
        @Override public float actinium$getParticleTextureJitterX() { return this.particleTextureJitterX; }
        @Override public float actinium$getParticleTextureJitterY() { return this.particleTextureJitterY; }
    }

    static final class PortalProbe extends ParticlePortal implements Probe, AccessorParticle, AccessorParticlePortal {

        private final float base = this.particleScale;

        PortalProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }

        @Override public float actinium$portalParticleScale() { return base; }
        @Override public int getBrightnessForRender(float partialTick) { return BRIGHTNESS; }
        @Override public void actinium$setParticleAge(int age) { this.particleAge = age; }
        @Override public double actinium$getPrevPosX() { return this.prevPosX; }
        @Override public double actinium$getPrevPosY() { return this.prevPosY; }
        @Override public double actinium$getPrevPosZ() { return this.prevPosZ; }
        @Override public double actinium$getPosX() { return this.posX; }
        @Override public double actinium$getPosY() { return this.posY; }
        @Override public double actinium$getPosZ() { return this.posZ; }
        @Override public int actinium$getParticleAge() { return this.particleAge; }
        @Override public int actinium$getParticleMaxAge() { return this.particleMaxAge; }
        @Override public float actinium$getParticleScale() { return this.particleScale; }
        @Override public void actinium$setParticleScale(float scale) { this.particleScale = scale; }
        @Override public float actinium$getParticleAlpha() { return this.particleAlpha; }
        @Override public void actinium$setParticleAlpha(float alpha) { this.particleAlpha = alpha; }
        @Override public TextureAtlasSprite actinium$getParticleTexture() { return this.particleTexture; }
        @Override public int actinium$getParticleTextureIndexX() { return this.particleTextureIndexX; }
        @Override public int actinium$getParticleTextureIndexY() { return this.particleTextureIndexY; }
        @Override public float actinium$getParticleTextureJitterX() { return this.particleTextureJitterX; }
        @Override public float actinium$getParticleTextureJitterY() { return this.particleTextureJitterY; }
    }

    static final class SmokeProbe extends ParticleSmokeNormal implements Probe, AccessorParticle, AccessorParticleSmokeNormal {

        private final float base = this.particleScale;

        SmokeProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D, 1.0F);
        }

        @Override public float actinium$smokeParticleScale() { return base; }
        @Override public int getBrightnessForRender(float partialTick) { return BRIGHTNESS; }
        @Override public void actinium$setParticleAge(int age) { this.particleAge = age; }
        @Override public double actinium$getPrevPosX() { return this.prevPosX; }
        @Override public double actinium$getPrevPosY() { return this.prevPosY; }
        @Override public double actinium$getPrevPosZ() { return this.prevPosZ; }
        @Override public double actinium$getPosX() { return this.posX; }
        @Override public double actinium$getPosY() { return this.posY; }
        @Override public double actinium$getPosZ() { return this.posZ; }
        @Override public int actinium$getParticleAge() { return this.particleAge; }
        @Override public int actinium$getParticleMaxAge() { return this.particleMaxAge; }
        @Override public float actinium$getParticleScale() { return this.particleScale; }
        @Override public void actinium$setParticleScale(float scale) { this.particleScale = scale; }
        @Override public float actinium$getParticleAlpha() { return this.particleAlpha; }
        @Override public void actinium$setParticleAlpha(float alpha) { this.particleAlpha = alpha; }
        @Override public TextureAtlasSprite actinium$getParticleTexture() { return this.particleTexture; }
        @Override public int actinium$getParticleTextureIndexX() { return this.particleTextureIndexX; }
        @Override public int actinium$getParticleTextureIndexY() { return this.particleTextureIndexY; }
        @Override public float actinium$getParticleTextureJitterX() { return this.particleTextureJitterX; }
        @Override public float actinium$getParticleTextureJitterY() { return this.particleTextureJitterY; }
    }

    static final class OverlayProbe extends ParticleFirework.Overlay implements Probe, AccessorParticle {

        OverlayProbe() {
            super(null, 4.0D, 5.0D, 6.0D);
        }

        @Override public int getBrightnessForRender(float partialTick) { return BRIGHTNESS; }
        @Override public void actinium$setParticleAge(int age) { this.particleAge = age; }
        @Override public double actinium$getPrevPosX() { return this.prevPosX; }
        @Override public double actinium$getPrevPosY() { return this.prevPosY; }
        @Override public double actinium$getPrevPosZ() { return this.prevPosZ; }
        @Override public double actinium$getPosX() { return this.posX; }
        @Override public double actinium$getPosY() { return this.posY; }
        @Override public double actinium$getPosZ() { return this.posZ; }
        @Override public int actinium$getParticleAge() { return this.particleAge; }
        @Override public int actinium$getParticleMaxAge() { return this.particleMaxAge; }
        @Override public float actinium$getParticleScale() { return this.particleScale; }
        @Override public void actinium$setParticleScale(float scale) { this.particleScale = scale; }
        @Override public float actinium$getParticleAlpha() { return this.particleAlpha; }
        @Override public void actinium$setParticleAlpha(float alpha) { this.particleAlpha = alpha; }
        @Override public TextureAtlasSprite actinium$getParticleTexture() { return this.particleTexture; }
        @Override public int actinium$getParticleTextureIndexX() { return this.particleTextureIndexX; }
        @Override public int actinium$getParticleTextureIndexY() { return this.particleTextureIndexY; }
        @Override public float actinium$getParticleTextureJitterX() { return this.particleTextureJitterX; }
        @Override public float actinium$getParticleTextureJitterY() { return this.particleTextureJitterY; }
    }

    static final class SparkProbe extends ParticleFirework.Spark implements Probe, AccessorParticle, AccessorParticleFireworkSpark {

        SparkProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D, null);
        }

        @Override public boolean actinium$twinkle() { return false; }
        @Override public int getBrightnessForRender(float partialTick) { return BRIGHTNESS; }
        @Override public void actinium$setParticleAge(int age) { this.particleAge = age; }
        @Override public double actinium$getPrevPosX() { return this.prevPosX; }
        @Override public double actinium$getPrevPosY() { return this.prevPosY; }
        @Override public double actinium$getPrevPosZ() { return this.prevPosZ; }
        @Override public double actinium$getPosX() { return this.posX; }
        @Override public double actinium$getPosY() { return this.posY; }
        @Override public double actinium$getPosZ() { return this.posZ; }
        @Override public int actinium$getParticleAge() { return this.particleAge; }
        @Override public int actinium$getParticleMaxAge() { return this.particleMaxAge; }
        @Override public float actinium$getParticleScale() { return this.particleScale; }
        @Override public void actinium$setParticleScale(float scale) { this.particleScale = scale; }
        @Override public float actinium$getParticleAlpha() { return this.particleAlpha; }
        @Override public void actinium$setParticleAlpha(float alpha) { this.particleAlpha = alpha; }
        @Override public TextureAtlasSprite actinium$getParticleTexture() { return this.particleTexture; }
        @Override public int actinium$getParticleTextureIndexX() { return this.particleTextureIndexX; }
        @Override public int actinium$getParticleTextureIndexY() { return this.particleTextureIndexY; }
        @Override public float actinium$getParticleTextureJitterX() { return this.particleTextureJitterX; }
        @Override public float actinium$getParticleTextureJitterY() { return this.particleTextureJitterY; }
    }
}
