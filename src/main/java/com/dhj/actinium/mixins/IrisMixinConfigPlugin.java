package com.dhj.actinium.mixins;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runtime gate for {@code mixins.actinium.iris.json}.
 *
 * <p>The particle mixin is not gated here. It wraps the two {@code Particle.renderParticle} call
 * sites with {@code @WrapOperation} and keeps one {@code Operation} that performs the original call,
 * so an external mod's plain {@code @Redirect} on the same sites can coexist with it, and its local
 * frustum test has to run for the batched path as well: a particle the batch takes never reaches the
 * vanilla call. The one gate left is for a mixin whose target class only exists when a mod is
 * installed.
 */
public final class IrisMixinConfigPlugin implements IMixinConfigPlugin {

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
