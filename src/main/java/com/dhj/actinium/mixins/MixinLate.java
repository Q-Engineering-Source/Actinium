package com.dhj.actinium.mixins;

import com.dhj.actinium.compat.dh.DhGlBufferDiagnosticsAbi;
import com.gtnewhorizon.gtnhlib.compat.Mods;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

@SuppressWarnings("unused")
public class MixinLate implements ILateMixinLoader {

    /**
     * Maps each late/conditional mixin config to the mod ids that gate it.
     * Declared in mixins.actinium.conditions.properties (the mixin loader does
     * not accept custom fields inside the config jsons).
     */
    private static final String CONDITIONS_RESOURCE = "mixins.actinium.conditions.properties";

    private static final Properties CONDITIONAL_CONFIGS = loadConditions();

    @Override
    public List<String> getMixinConfigs() {
        return configsFor(Mods::isModPresent);
    }

    /**
     * Returns the conditional configs whose gating expression matches the runtime.
     * The value syntax is {@code modA,modB|modC}: comma-separated requirements form an
     * AND group, {@code |} separates alternative groups, and a config loads when any
     * group matches. A requirement is a mod id, or {@code class:<binary name>} to probe
     * for a class instead (for compat layers gated on embedded third-party code rather
     * than a mod container). The {@code dh-buffer-diagnostics} token is a dedicated
     * resource-only ABI probe for the optional DH GL-buffer diagnostics mixin.
     */
    static List<String> configsFor(Predicate<String> loadedMods) {
        return configsFor(loadedMods, MixinLate::classPresent);
    }

    static List<String> configsFor(Predicate<String> loadedMods, Predicate<String> classPresent) {
        return configsFor(loadedMods, classPresent, ignored -> DhGlBufferDiagnosticsAbi.isCompatible());
    }

    static List<String> configsFor(Predicate<String> loadedMods, Predicate<String> classPresent,
                                   Predicate<String> dhBufferDiagnosticsAbiPresent) {
        List<String> mixins = new ArrayList<>();
        CONDITIONAL_CONFIGS.forEach((config, modList) -> {
            for (String alternative : ((String) modList).split("\\|")) {
                boolean allPresent = true;
                for (String requirement : alternative.split(",")) {
                    if (!requirementMet(requirement.trim(), loadedMods, classPresent,
                            dhBufferDiagnosticsAbiPresent)) {
                        allPresent = false;
                        break;
                    }
                }
                if (allPresent) {
                    mixins.add((String) config);
                    break;
                }
            }
        });
        return mixins;
    }

    private static final String CLASS_PREFIX = "class:";
    private static final String DH_BUFFER_DIAGNOSTICS_TOKEN = "dh-buffer-diagnostics";

    private static boolean requirementMet(String requirement, Predicate<String> loadedMods,
                                          Predicate<String> classPresent,
                                          Predicate<String> dhBufferDiagnosticsAbiPresent) {
        if (DH_BUFFER_DIAGNOSTICS_TOKEN.equals(requirement)) {
            return dhBufferDiagnosticsAbiPresent.test(requirement);
        }
        if (requirement.startsWith(CLASS_PREFIX)) {
            return classPresent.test(requirement.substring(CLASS_PREFIX.length()));
        }
        return loadedMods.test(requirement);
    }

    /** Resource-probes the class without initializing it. */
    private static boolean classPresent(String className) {
        String resource = className.replace('.', '/') + ".class";
        return MixinLate.class.getClassLoader().getResource(resource) != null;
    }

    private static Properties loadConditions() {
        final Properties properties = new Properties();
        try (InputStream stream = MixinLate.class.getClassLoader().getResourceAsStream(CONDITIONS_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing mixin condition declarations: " + CONDITIONS_RESOURCE);
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read mixin condition declarations: " + CONDITIONS_RESOURCE, e);
        }
        return properties;
    }
}
