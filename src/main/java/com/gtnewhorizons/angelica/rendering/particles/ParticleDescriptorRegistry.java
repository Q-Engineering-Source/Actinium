package com.gtnewhorizons.angelica.rendering.particles;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Chooses how one particle class is submitted to the instancer.
 *
 * <p>A class with a descriptor is described arithmetically; a class that inherits the vanilla
 * {@code renderParticle} uses the vanilla quad formula; every other class is captured, meaning its
 * own {@code renderParticle} runs into {@link ParticleCaptureBuffer} and the result is decoded.
 * Resolution is cached per class, including through the fast path field, because it runs once per
 * particle.</p>
 *
 * <h2>Why the dispatch looks the draw method up reflectively</h2>
 *
 * <p>Whether a particle class overrides {@code renderParticle} is what decides the descriptor: an
 * inherited body is the plain billboard the vanilla descriptor reproduces, an override is code the
 * batch has to run and decode. That distinction cannot be made statically by a plain method call,
 * because the call site sees only the {@link Particle} reference and Java dispatches to the override
 * at runtime, and the class set is open: particle classes come from every installed mod. The lookup
 * is therefore a single reflective {@code getMethod} per class, cached, and it also carries the
 * runtime names: the mod jar is reobfuscated, so the method exists as {@code renderParticle} in dev
 * and as its SRG name in production, and this framework is the one place that mapping lives instead
 * of every call site.</p>
 *
 * <p>The reflection is not a way around an unreachable entry point. An object of the particle class
 * is right there - {@code describe} receives it - so nothing here needs reflective construction,
 * field access or invocation of the particle itself; only the question "is this method the inherited
 * one?" is answered, and the answer is cached per class. If every particle type could be mapped
 * statically, this lookup would not be needed at all.</p>
 */
public final class ParticleDescriptorRegistry {

    private static final Logger LOGGER = LogManager.getLogger("Angelica/Particles");

    /**
     * Names the particle draw method can carry at runtime. The mod jar is reobfuscated, so the source
     * name is only present in dev; {@code func_180434_a} is the 1.12.2 SRG name and
     * {@code func_70539_a} the one 1.7.10 ports were written against.
     */
    private static final String[] RENDER_PARTICLE_NAMES = { "renderParticle", "func_180434_a", "func_70539_a" };

    private static final Class<?>[] RENDER_PARTICLE_PARAMETERS = {
        BufferBuilder.class, Entity.class, float.class, float.class, float.class, float.class, float.class, float.class
    };

    /** Descriptor of the capture path: the particle runs into the capture buffer instead. */
    public static final ParticleDescriptor CAPTURE = (particle, partialTicks, out, state) -> false;

    /** Descriptor of a particle that draws the plain billboarded quad at its current scale. */
    public static final ParticleDescriptor VANILLA = (particle, partialTicks, out, state) -> {
        ParticleQuads.vanillaQuad(particle, partialTicks, particle.particleScale, out);
        return true;
    };

    private static final Object2ObjectOpenHashMap<String, ParticleDescriptor> BY_NAME = new Object2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<Class<?>, ParticleDescriptor> BY_CLASS = new Reference2ObjectOpenHashMap<>();

    private static Class<?> lastClass;
    private static ParticleDescriptor lastDescriptor;

    static {
        VanillaParticleDescriptors.registerAll();
    }

    private ParticleDescriptorRegistry() {}

    public static void register(String className, ParticleDescriptor descriptor) {
        BY_NAME.put(className, descriptor);
        clearCache();
    }

    public static ParticleDescriptor forClass(Class<?> cls) {
        if (cls == lastClass) return lastDescriptor;
        ParticleDescriptor descriptor = BY_CLASS.get(cls);
        if (descriptor == null) {
            descriptor = resolve(cls);
            BY_CLASS.put(cls, descriptor);
        }
        lastClass = cls;
        lastDescriptor = descriptor;
        return descriptor;
    }

    public static void clearCache() {
        BY_CLASS.clear();
        lastClass = null;
        lastDescriptor = null;
    }

    private static ParticleDescriptor resolve(Class<?> cls) {
        final ParticleDescriptor registered = BY_NAME.get(cls.getName());
        if (registered != null) return registered;

        final Method render = renderParticleMethod(cls);
        if (render == null) {
            LOGGER.warn("Could not locate renderParticle on particle class {}, using the capture path", cls.getName());
            return CAPTURE;
        }
        return render.getDeclaringClass() == Particle.class ? VANILLA : CAPTURE;
    }

    /**
     * @return the particle draw method found under any of its runtime names, or {@code null}
     */
    static Method renderParticleMethod(Class<?> cls) {
        for (String name : RENDER_PARTICLE_NAMES) {
            try {
                return cls.getMethod(name, RENDER_PARTICLE_PARAMETERS);
            } catch (NoSuchMethodException ignored) {
                // Try the next runtime name.
            }
        }
        return null;
    }
}
