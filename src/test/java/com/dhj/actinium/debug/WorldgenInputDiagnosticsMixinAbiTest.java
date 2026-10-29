package com.dhj.actinium.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the compiled Minecraft target ABI and the actual compiled Mixin injection annotations. */
class WorldgenInputDiagnosticsMixinAbiTest {
    private static final String TARGET_RESOURCE =
        "net/minecraft/world/gen/ChunkGeneratorOverworld.class";
    private static final String TARGET_INTERNAL_NAME =
        "net/minecraft/world/gen/ChunkGeneratorOverworld";
    private static final String MIXIN_RESOURCE =
        "com/dhj/actinium/mixin/vintage/features/debug/MixinChunkGeneratorOverworldDiagnostics.class";
    private static final String REFMAP_RESOURCE = "mixins.actinium-refmap.json";
    private static final String CHUNK_DESCRIPTOR = "Lnet/minecraft/world/chunk/Chunk;";
    private static final String GENERATE_DESCRIPTOR = "(II)" + CHUNK_DESCRIPTOR;
    private static final String POPULATE_DESCRIPTOR = "(II)V";
    private static final String INJECT_DESCRIPTOR =
        "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String MIXIN_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String RETURNABLE_DESCRIPTOR =
        "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String CALLBACK_INFO_DESCRIPTOR =
        "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    @Test
    void generateAndPopulateHooksMatchCompiledMinecraftAbiAndReturnOpcodes() throws IOException {
        ClassLoader classLoader = WorldgenInputDiagnosticsMixinAbiTest.class.getClassLoader();
        ClassNode target = readClass(classLoader, TARGET_RESOURCE);
        ClassNode mixin = readClass(classLoader, MIXIN_RESOURCE);

        assertEquals(TARGET_INTERNAL_NAME, target.name);
        assertMixinTarget(mixin);

        assertTargetMethod(classLoader, mixin,
            "actinium$afterGenerateChunk", "(II" + RETURNABLE_DESCRIPTOR + ")V",
            "generateChunk" + GENERATE_DESCRIPTOR, Opcodes.ARETURN);
        assertTargetMethod(classLoader, mixin,
            "actinium$afterPopulate", "(II" + CALLBACK_INFO_DESCRIPTOR + ")V",
            "populate" + POPULATE_DESCRIPTOR, Opcodes.RETURN);
    }

    private static void assertMixinTarget(ClassNode mixin) {
        AnnotationNode mixinAnnotation = findAnnotation(mixin.visibleAnnotations, MIXIN_DESCRIPTOR);
        if (mixinAnnotation == null) {
            mixinAnnotation = findAnnotation(mixin.invisibleAnnotations, MIXIN_DESCRIPTOR);
        }
        assertNotNull(mixinAnnotation, "compiled class must retain @Mixin metadata");

        Object targets = annotationValue(mixinAnnotation, "value");
        assertTrue(containsType(targets, "L" + TARGET_INTERNAL_NAME + ";"),
            "@Mixin must target ChunkGeneratorOverworld.class");
    }

    private static void assertTargetMethod(ClassLoader classLoader, ClassNode mixin,
                                           String handlerName, String handlerDescriptor,
                                           String declaredTarget, int returnOpcode) throws IOException {
        MethodNode handler = mixin.methods.stream()
            .filter(method -> handlerName.equals(method.name) && handlerDescriptor.equals(method.desc))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing compiled handler "
                + handlerName + handlerDescriptor));

        AnnotationNode inject = findAnnotation(handler.visibleAnnotations, INJECT_DESCRIPTOR);
        if (inject == null) {
            inject = findAnnotation(handler.invisibleAnnotations, INJECT_DESCRIPTOR);
        }
        assertNotNull(inject, handlerName + " must carry compiled @Inject metadata");
        assertTrue(stringValues(annotationValue(inject, "method")).contains(declaredTarget),
            handlerName + " must target " + declaredTarget);
        assertReturnInjection(inject, handlerName);

        String expectedName = resolveTargetName(classLoader, mixin.name, declaredTarget);
        MethodNode target = findMethod(classLoader, expectedName, descriptorOf(declaredTarget));
        assertNotNull(target, "compiled target class must contain " + expectedName
            + descriptorOf(declaredTarget) + " (from " + declaredTarget + ")");
        assertTrue(hasOpcode(target, returnOpcode), "target " + expectedName + target.desc
            + " must contain the matching JVM return opcode " + returnOpcode);
    }

    private static void assertReturnInjection(AnnotationNode inject, String handlerName) {
        List<AnnotationNode> atAnnotations = annotationValues(annotationValue(inject, "at"));
        assertTrue(atAnnotations.stream().anyMatch(at -> {
            if (!AT_DESCRIPTOR.equals(at.desc)) {
                return false;
            }
            return "RETURN".equals(annotationValue(at, "value"));
        }), handlerName + " must inject at @At(\"RETURN\")");
    }

    private static String resolveTargetName(ClassLoader classLoader, String mixinInternalName,
                                            String declaredTarget) throws IOException {
        String declaredName = methodNameOf(declaredTarget);
        String descriptor = descriptorOf(declaredTarget);
        ClassNode target = readClass(classLoader, TARGET_RESOURCE);
        if (hasMethod(target, declaredName, descriptor)) {
            return declaredName;
        }

        Set<String> mappedNames = refmapTargetNames(classLoader, mixinInternalName, declaredTarget);
        return mappedNames.stream()
            .filter(name -> hasMethod(target, name, descriptor))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No target method " + declaredName + descriptor
                + " in compiled MCP class and no current refmap target matched " + mappedNames));
    }

    private static Set<String> refmapTargetNames(ClassLoader classLoader, String mixinInternalName,
                                                 String declaredTarget) throws IOException {
        try (InputStream stream = classLoader.getResourceAsStream(REFMAP_RESOURCE)) {
            if (stream == null) {
                return Set.of();
            }
            JsonObject refmap = JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            List<JsonObject> mappingTables = new ArrayList<>();
            addMappingTable(mappingTables, refmap.getAsJsonObject("mappings"), mixinInternalName);
            JsonObject data = refmap.getAsJsonObject("data");
            if (data != null) {
                for (String namespace : data.keySet()) {
                    addMappingTable(mappingTables, data.getAsJsonObject(namespace), mixinInternalName);
                }
            }

            LinkedHashSet<String> names = new LinkedHashSet<>();
            String methodName = methodNameOf(declaredTarget);
            String descriptor = descriptorOf(declaredTarget);
            for (JsonObject mappingTable : mappingTables) {
                for (String key : List.of(declaredTarget, methodName + descriptor, methodName)) {
                    JsonElement mapped = mappingTable.get(key);
                    if (mapped != null && mapped.isJsonPrimitive() && mapped.getAsJsonPrimitive().isString()) {
                        names.add(methodNameOf(mapped.getAsString()));
                    }
                }
            }
            return Set.copyOf(names);
        }
    }

    private static void addMappingTable(List<JsonObject> tables, JsonObject namespace,
                                        String mixinInternalName) {
        if (namespace == null) {
            return;
        }
        JsonObject table = namespace.getAsJsonObject(mixinInternalName);
        if (table == null) {
            table = namespace.getAsJsonObject(mixinInternalName.replace('/', '.'));
        }
        if (table != null) {
            tables.add(table);
        }
    }

    private static MethodNode findMethod(ClassLoader classLoader, String name, String descriptor)
        throws IOException {
        ClassNode target = readClass(classLoader, TARGET_RESOURCE);
        return target.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
            .findFirst()
            .orElse(null);
    }

    private static boolean hasMethod(ClassNode target, String name, String descriptor) {
        return target.methods.stream().anyMatch(method -> name.equals(method.name)
            && descriptor.equals(method.desc));
    }

    private static boolean hasOpcode(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode readClass(ClassLoader classLoader, String resourceName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            assertNotNull(stream, "Missing compiled class resource " + resourceName);
            new ClassReader(Objects.requireNonNull(stream, "Missing " + resourceName))
                .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream().filter(annotation -> descriptor.equals(annotation.desc))
            .findFirst().orElse(null);
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    private static List<String> stringValues(Object value) {
        if (value instanceof String string) {
            return List.of(string);
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static List<AnnotationNode> annotationValues(Object value) {
        if (value instanceof AnnotationNode annotation) {
            return List.of(annotation);
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        }
        return List.of();
    }

    private static boolean containsType(Object value, String descriptor) {
        if (value instanceof Type type) {
            return descriptor.equals(type.getDescriptor());
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(element -> containsType(element, descriptor));
        }
        return false;
    }

    private static String methodNameOf(String targetReference) {
        int descriptorIndex = targetReference.indexOf('(');
        String methodReference = descriptorIndex < 0
            ? targetReference
            : targetReference.substring(0, descriptorIndex);
        int ownerSeparator = methodReference.lastIndexOf(';');
        return ownerSeparator < 0 ? methodReference : methodReference.substring(ownerSeparator + 1);
    }

    private static String descriptorOf(String targetReference) {
        int descriptorIndex = targetReference.indexOf('(');
        if (descriptorIndex < 0) {
            throw new AssertionError("Mixin target reference has no descriptor: " + targetReference);
        }
        return targetReference.substring(descriptorIndex);
    }
}
