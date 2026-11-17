package com.dhj.actinium.mixin.vintage.features.particles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads and writes the {@link Particle} state the particle descriptors mirror.
 *
 * <p>Vanilla keeps position, age, scale, colour and sprite behind protected fields, and the
 * descriptors live outside {@code net.minecraft.client.particle}. Reading the real fields keeps the
 * instance payload identical to what the particle would have written; approximating the private
 * per-particle size fields from {@code particleScale} drifts on short lived particles.</p>
 */
@Mixin(Particle.class)
public interface AccessorParticle {

    @Accessor("prevPosX")
    double actinium$getPrevPosX();

    @Accessor("prevPosY")
    double actinium$getPrevPosY();

    @Accessor("prevPosZ")
    double actinium$getPrevPosZ();

    @Accessor("posX")
    double actinium$getPosX();

    @Accessor("posY")
    double actinium$getPosY();

    @Accessor("posZ")
    double actinium$getPosZ();

    @Accessor("particleAge")
    int actinium$getParticleAge();

    @Accessor("particleMaxAge")
    int actinium$getParticleMaxAge();

    @Accessor("particleScale")
    float actinium$getParticleScale();

    @Accessor("particleScale")
    void actinium$setParticleScale(float scale);

    @Accessor("particleAlpha")
    float actinium$getParticleAlpha();

    @Accessor("particleAlpha")
    void actinium$setParticleAlpha(float alpha);

    @Accessor("particleTexture")
    TextureAtlasSprite actinium$getParticleTexture();

    @Accessor("particleTextureIndexX")
    int actinium$getParticleTextureIndexX();

    @Accessor("particleTextureIndexY")
    int actinium$getParticleTextureIndexY();

    @Accessor("particleTextureJitterX")
    float actinium$getParticleTextureJitterX();

    @Accessor("particleTextureJitterY")
    float actinium$getParticleTextureJitterY();
}
