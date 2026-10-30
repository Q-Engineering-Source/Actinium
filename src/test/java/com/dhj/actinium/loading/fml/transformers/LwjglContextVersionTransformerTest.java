package com.dhj.actinium.loading.fml.transformers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LwjglContextVersionTransformerTest {
    private static final String CONTEXT_ATTRIBS = "org.lwjgl.opengl.ContextAttribs";
    private static final String CONTEXT_ATTRIBS_INTERNAL = "org/lwjgl/opengl/ContextAttribs";
    private static final String EARLY_PLUGIN = "com/dhj/actinium/mixins/MixinEarly";
    private static final String REGISTER_EXPLICIT =
        "(Ljava/util/Map;Ltop/outlands/foundation/IExplicitTransformer;Ljava/lang/String;)V";

    private final LwjglContextVersionTransformer transformer = new LwjglContextVersionTransformer();

    @Test
    void createsAndConfiguresGl46() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ContextAttributes(4, 6));
        Class<?> attributesType = load(transformer.transform(contextAttribsBytes()));
        Object attributes = attributesType.getConstructor(int.class, int.class).newInstance(4, 6);
        attributes = attributesType.getMethod("withProfileCore", boolean.class).invoke(attributes, true);
        attributes = attributesType.getMethod("withForwardCompatible", boolean.class).invoke(attributes, true);
        attributes = attributesType.getMethod("withDebug", boolean.class).invoke(attributes, true);

        assertEquals(4, attributesType.getMethod("getMajorVersion").invoke(attributes));
        assertEquals(6, attributesType.getMethod("getMinorVersion").invoke(attributes));
        assertTrue((Boolean) attributesType.getMethod("isProfileCore").invoke(attributes));
        assertTrue((Boolean) attributesType.getMethod("isForwardCompatible").invoke(attributes));
        assertTrue((Boolean) attributesType.getMethod("isDebug").invoke(attributes));
    }

    @Test
    void leavesUnrestrictedContextAttributesUntouched() throws Exception {
        byte[] original;
        try (InputStream stream = LwjglContextVersionTransformerTest.class.getClassLoader()
            .getResourceAsStream(CONTEXT_ATTRIBS_INTERNAL + ".class")) {
            assertNotNull(stream);
            original = stream.readAllBytes();
        }

        assertSame(original, transformer.transform(CONTEXT_ATTRIBS, CONTEXT_ATTRIBS, original));
    }

    @Test
    void preservesOtherVersionValidation() throws Exception {
        Class<?> attributesType = load(transformer.transform(contextAttribsBytes()));
        for (int[] version : List.of(new int[] {4, 7}, new int[] {5, 0})) {
            InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> attributesType.getConstructor(int.class, int.class).newInstance(version[0], version[1])
            );
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }

        Object attributes = attributesType.getConstructor(int.class, int.class).newInstance(4, 5);
        assertEquals(4, attributesType.getMethod("getMajorVersion").invoke(attributes));
        assertEquals(5, attributesType.getMethod("getMinorVersion").invoke(attributes));
    }

    @Test
    void normalAndExplicitPathsAreIdempotent() throws Exception {
        byte[] original = contextAttribsBytes();
        byte[] normal = transformer.transform(CONTEXT_ATTRIBS, CONTEXT_ATTRIBS, original);
        byte[] explicit = transformer.transform(original);

        assertArrayEquals(normal, explicit);
        assertSame(normal, transformer.transform(CONTEXT_ATTRIBS, CONTEXT_ATTRIBS, normal));
        assertSame(explicit, transformer.transform(explicit));
        assertSame(original, transformer.transform("java.lang.Object", "java.lang.Object", original));
    }

    @Test
    void earlyPluginRegistersSameTransformerForBothPaths() throws Exception {
        ClassNode early;
        try (InputStream stream = LwjglContextVersionTransformerTest.class.getClassLoader()
            .getResourceAsStream(EARLY_PLUGIN + ".class")) {
            assertNotNull(stream);
            early = readClass(stream.readAllBytes());
        }

        MethodNode getAsmTransformers = method(early, "getASMTransformerClass", "()[Ljava/lang/String;");
        Type versionType = Type.getType(LwjglContextVersionTransformer.class);
        assertTrue(meaningfulInstructions(getAsmTransformers).stream().anyMatch(instruction ->
            instruction instanceof LdcInsnNode ldc && versionType.equals(ldc.cst)));

        MethodNode registration = method(early, "registerExplicitTransformers", "()V");
        assertEquals(Opcodes.ACC_SYNCHRONIZED, registration.access & Opcodes.ACC_SYNCHRONIZED);
        List<AbstractInsnNode> instructions = meaningfulInstructions(registration);
        int versionRegistrations = 0;
        for (int index = 0; index + 2 < instructions.size(); index++) {
            if (instructions.get(index) instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC
                && EARLY_PLUGIN.equals(field.owner)
                && "EXPLICIT_VERSION_TRANSFORMER".equals(field.name)
                && instructions.get(index + 1) instanceof LdcInsnNode target
                && CONTEXT_ATTRIBS.equals(target.cst)
                && instructions.get(index + 2) instanceof MethodInsnNode call
                && "registerExplicitTransformer".equals(call.name)
                && REGISTER_EXPLICIT.equals(call.desc)) {
                versionRegistrations++;
            }
        }
        assertEquals(1, versionRegistrations);
    }

    private static Class<?> load(byte[] transformed) throws ClassNotFoundException {
        ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (CONTEXT_ATTRIBS.equals(name)) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = defineClass(name, transformed, 0, transformed.length);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        return Class.forName(CONTEXT_ATTRIBS, false, loader);
    }

    private static byte[] contextAttribsBytes() throws IOException {
        String fixture = Type.getInternalName(ContextAttributes.class);
        try (InputStream stream = LwjglContextVersionTransformerTest.class.getClassLoader()
            .getResourceAsStream(fixture + ".class")) {
            assertNotNull(stream);
            ClassReader reader = new ClassReader(stream);
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassRemapper(writer, new Remapper() {
                @Override
                public String map(String internalName) {
                    return fixture.equals(internalName) ? CONTEXT_ATTRIBS_INTERNAL : internalName;
                }
            }), 0);
            return writer.toByteArray();
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing method " + name + descriptor));
    }

    private static List<AbstractInsnNode> meaningfulInstructions(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) {
                instructions.add(instruction);
            }
        }
        return instructions;
    }

    public static final class ContextAttributes {
        private int majorVersion;
        private int minorVersion;
        private int profileMask;
        private int contextFlags;

        public ContextAttributes(int majorVersion, int minorVersion) {
            this(majorVersion, minorVersion, 0, 0);
        }

        public ContextAttributes(int majorVersion, int minorVersion, int profileMask, int contextFlags) {
            if (majorVersion < 0 || 4 < majorVersion
                || minorVersion < 0
                || (majorVersion == 4 && 5 < minorVersion)
                || (majorVersion == 3 && 3 < minorVersion)
                || (majorVersion == 2 && 1 < minorVersion)
                || (majorVersion == 1 && 5 < minorVersion)) {
                throw new IllegalArgumentException("Invalid OpenGL version specified: " + majorVersion + '.' + minorVersion);
            }
            this.majorVersion = majorVersion;
            this.minorVersion = minorVersion;
            this.profileMask = profileMask;
            this.contextFlags = contextFlags;
        }

        private ContextAttributes(ContextAttributes other) {
            majorVersion = other.majorVersion;
            minorVersion = other.minorVersion;
            profileMask = other.profileMask;
            contextFlags = other.contextFlags;
        }

        public int getMajorVersion() {
            return majorVersion;
        }

        public int getMinorVersion() {
            return minorVersion;
        }

        public ContextAttributes withProfileCore(boolean enabled) {
            ContextAttributes result = new ContextAttributes(this);
            result.profileMask = enabled ? 1 : 0;
            return result;
        }

        public ContextAttributes withForwardCompatible(boolean enabled) {
            ContextAttributes result = new ContextAttributes(this);
            result.contextFlags = enabled ? contextFlags | 2 : contextFlags & ~2;
            return result;
        }

        public ContextAttributes withDebug(boolean enabled) {
            ContextAttributes result = new ContextAttributes(this);
            result.contextFlags = enabled ? contextFlags | 1 : contextFlags & ~1;
            return result;
        }

        public boolean isProfileCore() {
            return profileMask == 1;
        }

        public boolean isForwardCompatible() {
            return (contextFlags & 2) != 0;
        }

        public boolean isDebug() {
            return (contextFlags & 1) != 0;
        }
    }
}
