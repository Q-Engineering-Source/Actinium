package com.dhj.actinium.mixins;

import com.dhj.actinium.loading.fml.transformers.BetterFoliageHbmCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.GnetumHudCachingCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.LateRedirectorInstaller;
import com.dhj.actinium.loading.fml.transformers.LwjglContextLifecycleTransformer;
import com.dhj.actinium.loading.fml.transformers.LwjglContextVersionTransformer;
import com.dhj.actinium.loading.fml.transformers.MacDisplayForwardCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.FastutilUpgraderTransformer;
import com.dhj.actinium.loading.fml.transformers.OutlandsExplicitTransformerRegistration;
import com.dhj.actinium.loading.fml.transformers.StellarCoreHudCachingCompatTransformer;
import com.dhj.actinium.loading.fml.transformers.SdlGpuHostTransformer;
import com.dhj.actinium.startup.SdlGpuBootstrap;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.MixinEnvironment;
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

    static {
        String specificationVersion = System.getProperty("java.specification.version");
        int featureVersion = specificationVersion.startsWith("1.")
            ? Integer.parseInt(specificationVersion.substring(2))
            : Integer.parseInt(specificationVersion);
        // CeleritasExtra uses Java 11 nesting features when the runtime supports them.
        MixinEnvironment.setCompatibilityLevel(featureVersion >= 11
            ? MixinEnvironment.CompatibilityLevel.JAVA_11
            : MixinEnvironment.CompatibilityLevel.JAVA_8);
    }

    @Override
    public @Nullable String[] getASMTransformerClass() {
        OutlandsExplicitTransformerRegistration.register();
        List<String> transformers = new ArrayList<>();
        // Cleanroom puts fastutil 8 on the launch classpath itself; only vanilla Forge needs its
        // bundled fastutil 7.1.0 classes replaced.
        if (!OutlandsExplicitTransformerRegistration.isAvailable()) {
            transformers.add(FastutilUpgraderTransformer.class.getName());
        }
        transformers.add(MacDisplayForwardCompatTransformer.class.getName());
        transformers.add(LwjglContextVersionTransformer.class.getName());
        transformers.add(LwjglContextLifecycleTransformer.class.getName());
        transformers.add(SdlGpuHostTransformer.class.getName());
        transformers.add(StellarCoreHudCachingCompatTransformer.class.getName());
        transformers.add(GnetumHudCachingCompatTransformer.class.getName());
        transformers.add("com.gtnewhorizons.angelica.loading.fml.transformers.EarlyRedirectorTransformer");
        transformers.add(BetterFoliageHbmCompatTransformer.class.getName());
        return transformers.toArray(new String[0]);
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
    public void injectData(Map<String, Object> data) {
        SdlGpuBootstrap.initialize();
        LateRedirectorInstaller.prepareTweaker();
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
