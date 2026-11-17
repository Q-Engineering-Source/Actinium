package com.gtnewhorizons.angelica.rendering.particles;

import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticle;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleCloud;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleCrit;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleFireworkSpark;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleFlame;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleHeart;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleLava;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleNote;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticlePortal;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleRedstone;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleSmokeNormal;
import com.dhj.actinium.mixin.vintage.features.particles.AccessorParticleSnowShovel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleBlockDust;
import net.minecraft.client.particle.ParticleBreaking;
import net.minecraft.client.particle.ParticleCloud;
import net.minecraft.client.particle.ParticleCrit;
import net.minecraft.client.particle.ParticleDigging;
import net.minecraft.client.particle.ParticleExplosionHuge;
import net.minecraft.client.particle.ParticleFirework;
import net.minecraft.client.particle.ParticleFlame;
import net.minecraft.client.particle.ParticleHeart;
import net.minecraft.client.particle.ParticleLava;
import net.minecraft.client.particle.ParticleNote;
import net.minecraft.client.particle.ParticlePortal;
import net.minecraft.client.particle.ParticleRedstone;
import net.minecraft.client.particle.ParticleSmokeNormal;
import net.minecraft.client.particle.ParticleSnowShovel;
import net.minecraft.client.particle.ParticleSpell;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.MathHelper;

/**
 * Descriptors for the vanilla 1.12.2 particle classes, transcribed from their own
 * {@code renderParticle} bodies.
 *
 * <p>Each descriptor repeats the arithmetic that method applies to {@code particleScale} and the
 * texture coordinates before delegating to the base billboard, including the side effects vanilla
 * performs on the particle (the animated scale and the firework overlay alpha). The per-class base
 * size lives in a private or package-private field, so it is read through the generated accessors
 * instead of being approximated from the current scale.</p>
 */
public final class VanillaParticleDescriptors {

    private interface BaseScale {

        float of(Particle particle);
    }

    private interface ScaleFactor {

        float factor(int age, int maxAge, float partialTicks);
    }

    private static final ScaleFactor RAMP = ParticleQuads::ramp;

    private static final ScaleFactor FLAME = (age, maxAge, partialTicks) -> {
        final float f = (age + partialTicks) / maxAge;
        return 1.0F - f * f * 0.5F;
    };

    private static final ScaleFactor LAVA = (age, maxAge, partialTicks) -> {
        final float f = (age + partialTicks) / maxAge;
        return 1.0F - f * f;
    };

    private static final ScaleFactor PORTAL = (age, maxAge, partialTicks) -> {
        float f = (age + partialTicks) / maxAge;
        f = 1.0F - f;
        f *= f;
        return 1.0F - f;
    };

    /** A particle whose {@code renderParticle} only rewrites the scale and then draws the base quad. */
    private record BaseScaled(BaseScale base, ScaleFactor factor) implements ParticleDescriptor {

        @Override
        public boolean describe(Particle particle, float partialTicks, ParticleParams out, ParticleRenderState state) {
            final AccessorParticle access = (AccessorParticle) particle;
            final float scale = base.of(particle) * factor.factor(access.actinium$getParticleAge(), access.actinium$getParticleMaxAge(), partialTicks);
            access.actinium$setParticleScale(scale);
            ParticleQuads.vanillaQuad(particle, partialTicks, scale, out);
            return true;
        }
    }

    private static final ParticleDescriptor EMPTY = (particle, partialTicks, out, state) -> false;

    private static final ParticleDescriptor DIGGING = (particle, partialTicks, out, state) -> {
        final AccessorParticle access = (AccessorParticle) particle;
        out.half = 0.1F * access.actinium$getParticleScale();
        ParticleQuads.center(particle, partialTicks, out);
        out.colorABGR = ParticleQuads.packColor(particle.getRedColorF(), particle.getGreenColorF(), particle.getBlueColorF(), 1.0F);
        final float jitterU = access.actinium$getParticleTextureJitterX();
        final float jitterV = access.actinium$getParticleTextureJitterY();
        final TextureAtlasSprite texture = access.actinium$getParticleTexture();
        if (texture != null) {
            out.u0 = texture.getInterpolatedU(jitterU / 4.0F * 16.0F);
            out.u1 = texture.getInterpolatedU((jitterU + 1.0F) / 4.0F * 16.0F);
            out.v1 = texture.getInterpolatedV(jitterV / 4.0F * 16.0F);
            out.v0 = texture.getInterpolatedV((jitterV + 1.0F) / 4.0F * 16.0F);
        } else {
            out.u0 = (access.actinium$getParticleTextureIndexX() + jitterU / 4.0F) / 16.0F;
            out.u1 = out.u0 + 0.015609375F;
            out.v1 = (access.actinium$getParticleTextureIndexY() + jitterV / 4.0F) / 16.0F;
            out.v0 = out.v1 + 0.015609375F;
        }
        return true;
    };

    private static final ParticleDescriptor FIREWORK_OVERLAY = (particle, partialTicks, out, state) -> {
        final AccessorParticle access = (AccessorParticle) particle;
        final float t = access.actinium$getParticleAge() + partialTicks - 1.0F;
        access.actinium$setParticleAlpha(0.6F - t * 0.25F * 0.5F);
        ParticleQuads.center(particle, partialTicks, out);
        out.half = 7.1F * MathHelper.sin(t * 0.25F * (float) Math.PI);
        out.u0 = 0.5F;
        out.v0 = 0.375F;
        out.u1 = 0.25F;
        out.v1 = 0.125F;
        out.colorABGR = ParticleQuads.packColor(particle);
        return true;
    };

    private static final ParticleDescriptor FIREWORK_SPARK = (particle, partialTicks, out, state) -> {
        final AccessorParticle access = (AccessorParticle) particle;
        final int age = access.actinium$getParticleAge();
        final int maxAge = access.actinium$getParticleMaxAge();
        if (((AccessorParticleFireworkSpark) particle).actinium$twinkle() && age >= maxAge / 3 && (age + maxAge) / 3 % 2 != 0) return false;
        ParticleQuads.vanillaQuad(particle, partialTicks, access.actinium$getParticleScale(), out);
        return true;
    };

    private VanillaParticleDescriptors() {}

    public static void registerAll() {
        registerScaled(ParticleFlame.class, particle -> ((AccessorParticleFlame) particle).actinium$flameScale(), FLAME);
        registerScaled(ParticleLava.class, particle -> ((AccessorParticleLava) particle).actinium$lavaParticleScale(), LAVA);
        registerScaled(ParticlePortal.class, particle -> ((AccessorParticlePortal) particle).actinium$portalParticleScale(), PORTAL);
        registerScaled(ParticleSmokeNormal.class, particle -> ((AccessorParticleSmokeNormal) particle).actinium$smokeParticleScale(), RAMP);
        registerScaled(ParticleCloud.class, particle -> ((AccessorParticleCloud) particle).actinium$oSize(), RAMP);
        registerScaled(ParticleCrit.class, particle -> ((AccessorParticleCrit) particle).actinium$oSize(), RAMP);
        registerScaled(ParticleHeart.class, particle -> ((AccessorParticleHeart) particle).actinium$particleScaleOverTime(), RAMP);
        registerScaled(ParticleNote.class, particle -> ((AccessorParticleNote) particle).actinium$noteParticleScale(), RAMP);
        registerScaled(ParticleRedstone.class, particle -> ((AccessorParticleRedstone) particle).actinium$reddustParticleScale(), RAMP);
        registerScaled(ParticleSnowShovel.class, particle -> ((AccessorParticleSnowShovel) particle).actinium$snowDigParticleScale(), RAMP);

        ParticleDescriptorRegistry.register(ParticleSpell.class.getName(), ParticleDescriptorRegistry.VANILLA);
        ParticleDescriptorRegistry.register(ParticleDigging.class.getName(), DIGGING);
        ParticleDescriptorRegistry.register(ParticleBlockDust.class.getName(), DIGGING);
        ParticleDescriptorRegistry.register(ParticleBreaking.class.getName(), DIGGING);
        ParticleDescriptorRegistry.register(ParticleFirework.Overlay.class.getName(), FIREWORK_OVERLAY);
        ParticleDescriptorRegistry.register(ParticleFirework.Spark.class.getName(), FIREWORK_SPARK);
        // 1.12.2 renders nothing for the huge explosion: its own renderParticle body is empty.
        ParticleDescriptorRegistry.register(ParticleExplosionHuge.class.getName(), EMPTY);
    }

    private static void registerScaled(Class<? extends Particle> cls, BaseScale base, ScaleFactor factor) {
        ParticleDescriptorRegistry.register(cls.getName(), new BaseScaled(base, factor));
    }
}
