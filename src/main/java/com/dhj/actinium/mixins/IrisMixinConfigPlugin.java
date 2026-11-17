package com.dhj.actinium.mixins;

import com.dhj.actinium.render.particle.ParticleViewportCulling;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runtime gate for {@code mixins.actinium.iris.json}.
 *
 * <p>ParticleCulling puts its own plain {@code @Redirect}s on the two
 * {@code Particle.renderParticle} call sites. Actinium wraps those invocations with
 * {@code @WrapOperation} and keeps one {@code Operation} that performs the original call, so the two
 * injectors coexist; what cannot coexist is two frustum tests running per particle. Presence of the
 * mod therefore only switches Actinium's own test off, through
 * {@code ParticleViewportCulling.setCullingDisabled}, and the mixin stays applied so the batch keeps
 * working. Presence is probed by asking the class loader for the ParticleCulling mixin resource
 * instead of {@code Loader.isModLoaded}, because {@code shouldApplyMixin} can be evaluated before mod
 * discovery has finished populating the loader's mod list; the probe is keyed on the marker class
 * name, so a ParticleCulling version that renames it would no longer be detected (the mod is not
 * updated for 1.12.2).
 */
public final class IrisMixinConfigPlugin implements IMixinConfigPlugin {

    private static final String CULLING_MIXIN = "com.dhj.actinium.mixin.features.iris.ParticleManagerCullingMixin";

    private static final String PARTICLE_CULLING_MARKER = "bl4ckscor3.mod.particleculling.mixin.MixinParticleManager";

    private static final String MEKANISM_GLOW_BLEND_MIXIN = "com.dhj.actinium.mixin.features.iris.MekanismGlowBlendMixin";

    private static final String MEKANISM_RENDERER_MARKER = "mekanism.client.render.MekanismRenderer";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return shouldApply(mixinClassName, IrisMixinConfigPlugin::classPresent);
    }

    static boolean shouldApply(String mixinClassName, Predicate<String> classPresent) {
        if (MEKANISM_GLOW_BLEND_MIXIN.equals(mixinClassName)) {
            return classPresent.test(MEKANISM_RENDERER_MARKER);
        }
        if (CULLING_MIXIN.equals(mixinClassName)) {
            ParticleViewportCulling.setCullingDisabled(classPresent.test(PARTICLE_CULLING_MARKER));
            return true;
        }
        return true;
    }

    /** Resource-probes the class without initializing it. */
    private static boolean classPresent(String className) {
        String resource = className.replace('.', '/') + ".class";
        return IrisMixinConfigPlugin.class.getClassLoader().getResource(resource) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
