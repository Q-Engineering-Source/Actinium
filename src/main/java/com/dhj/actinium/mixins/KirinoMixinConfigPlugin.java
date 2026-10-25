package com.dhj.actinium.mixins;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Runtime gate for {@code mixins.actinium.kirino.json}.
 *
 * <p>Kirino Engine's {@code KirinoConfigHub} is loaded very early ({@code KirinoCommonCore}
 * static initialisation runs inside {@code FMLClientHandler#beginMinecraftLoading}, before
 * the late mixin loader queues its configs), so a late/conditional config cannot target it:
 * the mixin would fail with {@code MixinTargetAlreadyLoadedException}. The config is
 * therefore registered by {@link MixinEarly}, and this plugin makes the application
 * conditional at transform time: when Kirino is absent its classes never load and the mixin
 * is a no-op, and when Kirino is present {@code KirinoConfigHub} is transformed on first
 * load and the mixin applies, pinning {@code isEnableRenderDelegate()} to {@code false}.
 * No Kirino class is referenced here; the gate is a plain class-name check.
 */
public final class KirinoMixinConfigPlugin implements IMixinConfigPlugin {

    private static final String KIRINO_TARGET = "com.cleanroommc.kirino.config.KirinoConfigHub";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // If the target is loaded at all, Kirino is present. The early config is queued
        // before any Kirino class loads, so if the target is being transformed now, the
        // mixin must be applied; any other target is never ours.
        return KIRINO_TARGET.equals(targetClassName);
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
