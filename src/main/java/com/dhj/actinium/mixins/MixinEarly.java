package com.dhj.actinium.mixins;

import com.dhj.actinium.loading.fml.transformers.BetterFoliageHbmCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.GnetumHudCachingCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.LwjglContextLifecycleTransformer;
import com.dhj.actinium.loading.fml.transformers.MacDisplayForwardCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.StellarCoreHudCachingCompatTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.MixinEnvironment;
import top.outlands.foundation.TransformerDelegate;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@IFMLLoadingPlugin.Name("Actinium")
@IFMLLoadingPlugin.MCVersion("1.12.2")
// Better Foliage sorts at 1400; this keeps the RenderChunk repair after its coremod transform.
@IFMLLoadingPlugin.SortingIndex(1500)
public class MixinEarly implements IFMLLoadingPlugin, IEarlyMixinLoader {
    private static final List<String> MIXIN_CONFIGS = List.of(
        "mixins.actinium.vintage.json",
        "mixins.actinium.iris.json",
        "mixins.actinium.hbm.early.json",
        "mixins.actinium.kirino.json"
    );
    private static final List<String> EXPLICIT_LIFECYCLE_TARGETS = List.of(
        "org.lwjgl.opengl.Display",
        "org.lwjgl.opengl.DrawableGL",
        "org.lwjgl.opengl.ContextGL"
    );
    private static final LwjglContextLifecycleTransformer EXPLICIT_LIFECYCLE_TRANSFORMER =
        new LwjglContextLifecycleTransformer();

    static {
        // CeleritasExtra uses Java 11 nesting features in its mixins.
        MixinEnvironment.setCompatibilityLevel(MixinEnvironment.CompatibilityLevel.JAVA_11);
    }

    @Override
    public @Nullable String[] getASMTransformerClass() {
        registerExplicitLifecycleTransformer();
        return new String[] {
            MacDisplayForwardCompatTransformer.class.getName(),
            LwjglContextLifecycleTransformer.class.getName(),
            StellarCoreHudCachingCompatTransformer.class.getName(),
            GnetumHudCachingCompatTransformer.class.getName(),
            "com.gtnewhorizons.angelica.loading.fml.transformers.EarlyRedirectorTransformer",
            BetterFoliageHbmCompatTransformer.class.getName()
        };
    }

    private static synchronized void registerExplicitLifecycleTransformer() {
        final var registry = TransformerDelegate.getExplicitTransformers();
        for (String target : EXPLICIT_LIFECYCLE_TARGETS) {
            final var transformers = registry.get(target);
            if (transformers == null
                || transformers.stream().noneMatch(transformer -> transformer == EXPLICIT_LIFECYCLE_TRANSFORMER)) {
                TransformerDelegate.registerExplicitTransformer(EXPLICIT_LIFECYCLE_TRANSFORMER, target);
            }
        }
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void injectData(Map<String, Object> data) {
        Object value = Launch.blackboard.get("TweakClasses");
        List<String> tweaks = null;
        if (value instanceof List<?>) {
            tweaks = (List<String>) value;
        }
        if (tweaks == null) {
            tweaks = new ArrayList<>();
            Launch.blackboard.put("TweakClasses", tweaks);
        }
        if (!tweaks.contains("com.gtnewhorizons.angelica.loading.fml.tweakers.AngelicaLateTweaker")) {
            tweaks.add("com.gtnewhorizons.angelica.loading.fml.tweakers.AngelicaLateTweaker");
        }
    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        return getEarlyMixinConfigs();
    }

    public static List<String> getEarlyMixinConfigs() {
        return MIXIN_CONFIGS;
    }
}
