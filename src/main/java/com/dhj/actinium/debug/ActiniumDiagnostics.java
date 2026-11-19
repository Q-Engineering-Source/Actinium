package com.dhj.actinium.debug;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.mixins.MixinEarly;
import com.dhj.actinium.mixins.MixinLate;
import com.dhj.actinium.runtime.ActiniumRuntime;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ActiniumDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumDiagnostics");
    private static final Set<String> APPLIED_MIXINS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> GIBBED_RENDER_PATHS = Collections.synchronizedSet(new LinkedHashSet<>());

    private ActiniumDiagnostics() {
    }

    public static boolean isEnabled() {
        String override = System.getProperty("actinium.productionDiagnostics");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }

        try {
            return ActiniumRuntime.options().debug.enableProductionDiagnostics;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    public static void logConstruction() {
        if (!isEnabled()) {
            return;
        }

        LOGGER.info(
            "construction env={} side=client java={} os={} coremod={} mixinConfigs={}",
            isDeobfuscatedEnvironment() ? "dev" : "production",
            System.getProperty("java.version"),
            System.getProperty("os.name") + " " + System.getProperty("os.version"),
            System.getProperty("fml.coreMods.load", ""),
            describeMixinConfigs()
        );
    }

    public static void logInitialization(String version) {
        if (!isEnabled()) {
            return;
        }

        LOGGER.info("init version={} config={}", version, describeConfig());
    }

    public static void recordMixinApplied(String targetClassName, String mixinClassName) {
        if (!isEnabled() || !isKeyMixin(mixinClassName)) {
            return;
        }

        String entry = mixinClassName + " -> " + targetClassName;
        if (APPLIED_MIXINS.add(entry)) {
            LOGGER.info("mixin-applied {}", entry);
        }
    }

    public static void recordGibbedRenderPath(String path) {
        if (!isEnabled()) {
            return;
        }

        if (GIBBED_RENDER_PATHS.add(path)) {
            LOGGER.info("gibbed-render-path {}", path);
        }
    }

    private static boolean isKeyMixin(String mixinClassName) {
        return mixinClassName.startsWith("com.dhj.actinium.mixin.features.iris.")
            || mixinClassName.startsWith("com.dhj.actinium.mixin.mod.gibbed.")
            || mixinClassName.startsWith("com.dhj.actinium.mixin.core.terrain.")
            || mixinClassName.startsWith("com.dhj.actinium.mixin.vintage.core.");
    }

    private static String describeMixinConfigs() {
        String earlyConfigs = String.join(",", MixinEarly.getEarlyMixinConfigs());
        String actiniumLateConfigs = String.join(",", new MixinLate().getMixinConfigs());

        if (actiniumLateConfigs.isEmpty()) {
            return earlyConfigs;
        }

        return earlyConfigs + "," + actiniumLateConfigs;
    }

    private static String describeConfig() {
        try {
            var options = ActiniumRuntime.options();
            return "advanced{multiDraw=" + options.advanced.multiDrawMode
                + ",streaming=" + options.advanced.streamingUploadStrategy
                + ",directMemory=" + ActiniumRuntimeOptions.allowDirectMemoryAccess()
                + ",modelRendererBatching=" + ActiniumRuntimeOptions.useModelRendererBatching()
                + ",modelRendererDisplayLists=" + ActiniumRuntimeOptions.useModelRendererDisplayLists()
                + ",fastLitItemRendering=" + ActiniumRuntimeOptions.useFastLitItemRendering()
                + ",fastLitItemDisplayLists=" + ActiniumRuntimeOptions.useFastLitItemDisplayLists()
                + "} debug{gl=" + options.debug.enableActiniumGlDebug
                + ",perf=" + options.debug.enableActiniumPerfDebug
                 + ",gpuPerf=" + options.debug.enableActiniumGpuPerfDebug
                 + ",pbr=" + ActiniumRuntimeOptions.pbrDebugEnabled()
                 + ",preGlError=" + options.debug.enableFrameGlErrorCheck
                + ",postGlError=" + options.debug.enablePostRenderGlErrorCheck
                + ",diagnostics=" + options.debug.enableProductionDiagnostics
                + ",entityRender=" + options.debug.enableEntityRenderDiagnostics
                + ",redirectorDebug=" + options.debug.enableRedirectorDebug
                + ",redirectorLogSpam=" + options.debug.enableRedirectorLogSpam
                + ",redirectorClassDump=" + options.debug.enableRedirectorClassDump
                + "}";
        } catch (RuntimeException e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static boolean isDeobfuscatedEnvironment() {
        Object value = Launch.blackboard.get("fml.deobfuscatedEnvironment");
        return value instanceof Boolean && (Boolean) value;
    }
}
