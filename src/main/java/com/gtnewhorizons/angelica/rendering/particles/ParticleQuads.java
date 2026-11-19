package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.MathHelper;

/**
 * The quad geometry shared by the base particle and by the descriptors that only replace the
 * texture coordinates or the size ramp.
 *
 * <p>The four corner texture coordinates are stored as {@code (u0,v0)} at corner 0 and
 * {@code (u1,v1)} at corner 2 in the corner order of
 * {@code com.gtnewhorizons.angelica.glsm.ffp.ParticleQuadMesh}, which reproduces the vanilla
 * diagonal {@code (maxU,maxV)} {@code (maxU,minV)} {@code (minU,minV)} {@code (minU,maxV)}.</p>
 */
public final class ParticleQuads {

    private ParticleQuads() {}

    public static void vanillaQuad(Particle particle, float partialTicks, float scale, ParticleParams out) {
        out.half = 0.1F * scale;
        center(particle, partialTicks, out);
        iconUv(particle, out);
        out.colorABGR = packColor(particle);
    }

    static void center(Particle particle, float partialTicks, ParticleParams out) {
        out.centerX = (float) (particle.prevPosX + (particle.posX - particle.prevPosX) * partialTicks - Particle.interpPosX);
        out.centerY = (float) (particle.prevPosY + (particle.posY - particle.prevPosY) * partialTicks - Particle.interpPosY);
        out.centerZ = (float) (particle.prevPosZ + (particle.posZ - particle.prevPosZ) * partialTicks - Particle.interpPosZ);
    }

    static void iconUv(Particle particle, ParticleParams out) {
        float minU = particle.particleTextureIndexX / 16.0F;
        float maxU = minU + 0.0624375F;
        float minV = particle.particleTextureIndexY / 16.0F;
        float maxV = minV + 0.0624375F;
        final TextureAtlasSprite texture = particle.particleTexture;
        if (texture != null) {
            minU = texture.getMinU();
            maxU = texture.getMaxU();
            minV = texture.getMinV();
            maxV = texture.getMaxV();
        }
        out.u0 = maxU;
        out.v0 = maxV;
        out.u1 = minU;
        out.v1 = minV;
    }

    static float ramp(int age, int maxAge, float partialTicks) {
        return MathHelper.clamp((age + partialTicks) / maxAge * 32.0F, 0.0F, 1.0F);
    }

    static int packColor(Particle particle) {
        return packColor(particle.getRedColorF(), particle.getGreenColorF(), particle.getBlueColorF(), particle.particleAlpha);
    }

    public static int packColor(float r, float g, float b, float a) {
        return (channel(a) << 24) | (channel(b) << 16) | (channel(g) << 8) | channel(r);
    }

    /**
     * One colour channel as {@code BufferBuilder.color} stores it: the truncated product is written
     * as a byte, so a value outside {@code [0,1]} wraps instead of being clamped. The firework
     * overlay drives its alpha negative on its last frames, and clamping there would draw it
     * differently from vanilla.
     */
    private static int channel(float value) {
        return ((int) (value * 255.0F)) & 0xFF;
    }
}
