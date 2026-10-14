package com.dhj.actinium.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinConfigurationTest {
    private static final String MIXIN_DESCRIPTOR = Type.getDescriptor(Mixin.class);
    private static final String EXPECTED_REFMAP = "mixins.actinium-refmap.json";
    private static final List<String> MAIN_CONFIGS = List.of(
        "mixins.actinium.vintage.json",
        "mixins.actinium.iris.json",
        "mixins.actinium.gibbed.json",
        "mixins.actinium.ichunutil.json",
        "mixins.actinium.lumenized.json",
        "mixins.actinium.revoui.json",
        "mixins.actinium.betterfoliage.json",
        "mixins.actinium.rlfoliage.json",
        "mixins.actinium.ccl.json",
        "mixins.actinium.voxelmap.json",
        "mixins.actinium.extrautils2.json",
        "mixins.actinium.cofhcore.json",
        "mixins.actinium.oldresearch.json",
        "mixins.actinium.botania.json",
        "mixins.actinium.hbm.json",
        "mixins.actinium.hbm.early.json",
        "mixins.actinium.kirino.json",
        "mixins.actinium.storagedrawers.json",
        "mixins.actinium.scannable.json",
        "mixins.actinium.littletiles.json",
        "mixins.actinium.architecturecraft.json",
        "mixins.actinium.gvclib.json",
        "mixins.actinium.obscuretooltips.json"
    );
    private static final List<String> CONFIGS = MAIN_CONFIGS;

    @Test
    void everyDeclaredMixinClassExists() throws IOException {
        ClassLoader classLoader = MixinConfigurationTest.class.getClassLoader();

        for (String configName : CONFIGS) {
            JsonObject config = readConfig(classLoader, configName);
            String packageName = config.get("package").getAsString();
            assertDeclaredClassesExist(classLoader, configName, packageName, config.getAsJsonArray("mixins"));
            assertDeclaredClassesExist(classLoader, configName, packageName, config.getAsJsonArray("client"));
            assertDeclaredClassesExist(classLoader, configName, packageName, config.getAsJsonArray("server"));
        }
    }

    @Test
    void everyCompiledMainMixinIsDeclaredExactlyOnce() throws IOException, URISyntaxException {
        ClassLoader classLoader = MixinConfigurationTest.class.getClassLoader();
        Map<String, String> declaredMixins = declaredMainMixins(classLoader);
        Set<String> compiledMixins = compiledMainMixins(classLoader);

        assertEquals(compiledMixins, declaredMixins.keySet());
    }

    @Test
    void earlyAndLateLoadersCoverEveryMainMixinConfig() throws IOException {
        ClassLoader classLoader = MixinConfigurationTest.class.getClassLoader();
        Set<String> earlyConfigs = compiledMixinConfigs(
            classLoader, "com/dhj/actinium/mixins/MixinEarly.class");
        // MixinLate loads its configs from mixins.actinium.conditions.properties.
        Set<String> lateConfigs = readConditions(classLoader).keySet().stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> allLoadedConfigs = new HashSet<>(earlyConfigs);

        assertTrue(earlyConfigs.stream().noneMatch(lateConfigs::contains),
            "A Mixin config cannot be both early and late");
        allLoadedConfigs.addAll(lateConfigs);
        assertEquals(Set.copyOf(MAIN_CONFIGS), allLoadedConfigs);
    }

    @Test
    void mainConfigsDeclareExpectedMetadataAndRefmapNames() throws IOException {
        ClassLoader classLoader = MixinConfigurationTest.class.getClassLoader();

        for (String configName : MAIN_CONFIGS) {
            JsonObject config = readConfig(classLoader, configName);
            assertEquals("0.8.5", config.get("minVersion").getAsString(), configName + " minVersion");
            assertEquals("JAVA_8", config.get("compatibilityLevel").getAsString(), configName + " compatibilityLevel");
            assertTrue(config.get("required").getAsBoolean(), configName + " required");

            String refmap = config.get("refmap").getAsString();
            assertFalse(refmap.isBlank(), configName + " must declare a non-empty refmap");
            assertEquals(EXPECTED_REFMAP, refmap, configName + " refmap");
        }
    }

    @Test
    void conditionalConfigsDeclareModRequirements() throws IOException {
        ClassLoader classLoader = MixinConfigurationTest.class.getClassLoader();
        final Set<String> earlyConfigs = compiledMixinConfigs(
            classLoader, "com/dhj/actinium/mixins/MixinEarly.class");
        final Set<String> lateConfigs = readConditions(classLoader).keySet().stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.toSet());
        final Properties conditions = readConditions(classLoader);

        for (String configName : MAIN_CONFIGS) {
            boolean isLate = lateConfigs.contains(configName);
            boolean isEarly = earlyConfigs.contains(configName);
            if (isLate) {
                String mods = conditions.getProperty(configName);
                assertTrue(mods != null && !mods.isBlank(),
                    configName + " is loaded by MixinLate and must declare mod ids in mixins.actinium.conditions.properties");
                assertFalse(isEarly, configName + " cannot be both early and late");
            } else if (isEarly) {
                assertFalse(conditions.containsKey(configName),
                    configName + " is loaded by MixinEarly (unconditional) and must not appear in the conditions file");
            }
        }
        assertEquals(lateConfigs, conditions.keySet(),
            "Every config in the conditions file must be one of the main mixin configs");
    }

    private static Properties readConditions(ClassLoader classLoader) throws IOException {
        try (InputStream stream = classLoader.getResourceAsStream("mixins.actinium.conditions.properties")) {
            assertNotNull(stream, "Missing mixins.actinium.conditions.properties");
            final Properties properties = new Properties();
            properties.load(stream);
            return properties;
        }
    }

    private static Set<String> compiledMixinConfigs(ClassLoader classLoader, String resourceName)
        throws IOException {
        // The loader collects its config strings in <clinit> and any helper
        // methods it calls; scan every method body so refactors that move the
        // literals into a builder method keep the test meaningful.
        ClassNode node = readClass(classLoader, resourceName);
        Set<String> configs = new HashSet<>();
        for (MethodNode method : node.methods) {
            collectConfigLiterals(method, configs);
        }
        return configs;
    }

    private static void collectConfigLiterals(MethodNode method, Set<String> configs) {
        for (var instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode ldc
                && ldc.cst instanceof String value
                && value.endsWith(".json")) {
                configs.add(value);
            }
        }
    }

    private static JsonObject readConfig(ClassLoader classLoader, String configName) throws IOException {
        try (Reader reader = new InputStreamReader(
            Objects.requireNonNull(classLoader.getResourceAsStream(configName), "Missing " + configName),
            StandardCharsets.UTF_8
        )) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Map<String, String> declaredMainMixins(ClassLoader classLoader) throws IOException {
        Map<String, String> declarations = new HashMap<>();
        for (String configName : MAIN_CONFIGS) {
            JsonObject config = readConfig(classLoader, configName);
            String packageName = config.get("package").getAsString();
            addDeclarations(declarations, configName, packageName, config.getAsJsonArray("mixins"));
            addDeclarations(declarations, configName, packageName, config.getAsJsonArray("client"));
            addDeclarations(declarations, configName, packageName, config.getAsJsonArray("server"));
        }
        return declarations;
    }

    private static void addDeclarations(
        Map<String, String> declarations,
        String configName,
        String packageName,
        JsonArray entries
    ) {
        if (entries == null) {
            return;
        }
        for (var entry : entries) {
            String className = packageName + "." + entry.getAsString();
            assertNull(declarations.put(className, configName), className + " is declared by multiple Mixin configs");
        }
    }

    private static Set<String> compiledMainMixins(ClassLoader classLoader)
        throws IOException, URISyntaxException {
        String anchorResource = "com/dhj/actinium/mixin/core/terrain/BufferBuilderMixin.class";
        URL anchorUrl = Objects.requireNonNull(classLoader.getResource(anchorResource), "Missing " + anchorResource);
        assertEquals("file", anchorUrl.getProtocol(), "Compiled Mixin classes must be available as files during tests");

        Path mixinRoot = Path.of(anchorUrl.toURI()).getParent().getParent().getParent();
        Set<String> mixins = new HashSet<>();
        try (Stream<Path> paths = Files.walk(mixinRoot)) {
            for (Path classFile : paths.filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                ClassNode node = new ClassNode();
                try (var stream = Files.newInputStream(classFile)) {
                    new ClassReader(stream).accept(node,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                if (hasMixinAnnotation(node.visibleAnnotations) || hasMixinAnnotation(node.invisibleAnnotations)) {
                    mixins.add(node.name.replace('/', '.'));
                }
            }
        }
        return mixins;
    }

    private static boolean hasMixinAnnotation(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation -> MIXIN_DESCRIPTOR.equals(annotation.desc));
    }

    private static ClassNode readClass(ClassLoader classLoader, String resourceName) throws IOException {
        ClassNode node = new ClassNode();
        try (var stream = Objects.requireNonNull(classLoader.getResourceAsStream(resourceName),
            "Missing " + resourceName)) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static void assertDeclaredClassesExist(
        ClassLoader classLoader,
        String configName,
        String packageName,
        JsonArray declarations
    ) {
        if (declarations == null) {
            return;
        }

        for (var declaration : declarations) {
            String className = packageName + "." + declaration.getAsString();
            String resourceName = className.replace('.', '/') + ".class";
            assertNotNull(classLoader.getResource(resourceName), configName + " references missing " + className);
            assertFalse(className.contains(".."), configName + " contains an invalid class name");
        }
    }
}
