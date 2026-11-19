package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers how a particle class is routed to a descriptor, including the runtime names the particle
 * draw method carries once the mod jar is reobfuscated.
 */
class ParticleDescriptorRegistryTest {

    @BeforeEach
    void resetCamera() {
        Particle.interpPosX = 0.0;
        Particle.interpPosY = 0.0;
        Particle.interpPosZ = 0.0;
        Particle.cameraViewDir = new Vec3d(0.0, 0.0, 1.0);
        ParticleDescriptorRegistry.clearCache();
    }

    @Test
    void findsTheDrawMethodUnderItsSourceName() {
        assertNotNull(ParticleDescriptorRegistry.renderParticleMethod(DevNamed.class));
    }

    @Test
    void findsTheDrawMethodUnderItsRuntimeNames() {
        assertNotNull(ParticleDescriptorRegistry.renderParticleMethod(SrgNamed.class),
            "the 1.12.2 SRG name has to be found as well");
        assertNotNull(ParticleDescriptorRegistry.renderParticleMethod(LegacyNamed.class),
            "the name 1.7.10 ports were written against stays supported");
    }

    @Test
    void reportsNoMethodWhenNoNameMatches() {
        assertNull(ParticleDescriptorRegistry.renderParticleMethod(Unnamed.class));
    }

    @Test
    void inheritingParticleDrawResolvesToTheVanillaDescriptor() {
        ParticleDescriptorRegistry.clearCache();

        assertSame(ParticleDescriptorRegistry.VANILLA, ParticleDescriptorRegistry.forClass(Inheriting.class));
    }

    @Test
    void overridingParticleDrawResolvesToTheCapturePath() {
        ParticleDescriptorRegistry.clearCache();

        assertSame(ParticleDescriptorRegistry.CAPTURE, ParticleDescriptorRegistry.forClass(Overriding.class));
    }

    /** Declares the draw method under the source name only. */
    static final class DevNamed {
        public void renderParticle(BufferBuilder buffer, Entity entity, float partialTicks, float rotationX,
                                   float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
        }
    }

    /** Declares the draw method under the 1.12.2 SRG name only. */
    static final class SrgNamed {
        public void func_180434_a(BufferBuilder buffer, Entity entity, float partialTicks, float rotationX,
                                  float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
        }
    }

    /** Declares the draw method under the 1.7.10 SRG name only. */
    static final class LegacyNamed {
        public void func_70539_a(BufferBuilder buffer, Entity entity, float partialTicks, float rotationX,
                                 float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
        }
    }

    /** Declares no draw method at all. */
    static final class Unnamed {
    }

    static class Inheriting extends Particle {
        Inheriting() {
            super(null, 0.0D, 0.0D, 0.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return 0;
        }
    }

    static final class Overriding extends Inheriting {
        @Override
        public void renderParticle(BufferBuilder buffer, Entity entity, float partialTicks, float rotationX,
                                   float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        }
    }
}
