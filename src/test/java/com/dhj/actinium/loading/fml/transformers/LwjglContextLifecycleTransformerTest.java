package com.dhj.actinium.loading.fml.transformers;

import com.gtnewhorizons.angelica.glsm.backend.GlfwWindowBackend;
import com.gtnewhorizons.angelica.glsm.backend.SdlWindowBackend;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LwjglContextLifecycleTransformerTest {
    private static final String DISPLAY = "org.lwjgl.opengl.Display";
    private static final String DRAWABLE_GL = "org.lwjgl.opengl.DrawableGL";
    private static final String CONTEXT_GL = "org.lwjgl.opengl.ContextGL";
    private static final String DISPLAY_INTERNAL = "org/lwjgl/opengl/Display";
    private static final String DRAWABLE_GL_INTERNAL = "org/lwjgl/opengl/DrawableGL";
    private static final String CONTEXT_GL_INTERNAL = "org/lwjgl/opengl/ContextGL";
    private static final String DISPLAY_BINDING = "com/dhj/actinium/compat/lwjgl/DisplayContextBinding";
    private static final String NATIVE_CONTEXT_HANDLE =
        "com/gtnewhorizons/angelica/glsm/context/NativeContextHandle";
    private static final String NATIVE_HANDLE_METHOD = "actinium$nativeHandle";
    private static final String WRONG_HANDLE_FIELD = "wrongHandle";
    private static final String DRAWABLE_DESCRIPTOR = "Lorg/lwjgl/opengl/DrawableGL;";
    private static final String CONTEXT_DESCRIPTOR = "Lorg/lwjgl/opengl/ContextGL;";
    private static final List<String> LIFECYCLE_TARGETS = List.of(DISPLAY, DRAWABLE_GL, CONTEXT_GL);

    private final LwjglContextLifecycleTransformer transformer = new LwjglContextLifecycleTransformer();

    @AfterEach
    void restoreGlfwBackend() {
        WindowBackends.useGlfw();
    }

    @Test
    void glfwLayoutKeepsLifecycleCallbacks() {
        FixtureSet transformed = transformAll(fixtures(Layout.GLFW));
        assertDisplayLifecycle(transformed.display());
        assertDrawableLifecycle(transformed.drawable());
        assertContextLifecycle(readClass(transformed.context()), Layout.GLFW.handleField);
    }

    @Test
    void bothLayoutsInjectNativeHandleMethods() {
        for (Layout layout : Layout.values()) {
            ClassNode context = readClass(transformer.transform(CONTEXT_GL, CONTEXT_GL, contextFixture(layout)));
            assertNativeHandleMethod(context, layout.handleField);
            assertNativeFieldReads(context, layout.handleField);
        }
    }

    @Test
    void sdlLayoutInjectsHandleAndLifecycleCallbacks() {
        verifyLayout(transformAll(fixtures(Layout.SDL)), Layout.SDL);
    }

    @Test
    void repeatedTransformationIsIdempotentForBothLayouts() {
        for (Layout layout : Layout.values()) {
            FixtureSet transformed = transformAll(fixtures(layout));
            FixtureSet repeated = transformAll(transformed);

            assertSame(transformed.display(), repeated.display());
            assertSame(transformed.drawable(), repeated.drawable());
            assertSame(transformed.context(), repeated.context());
            verifyLayout(transformed, layout);
        }
    }

    @Test
    void explicitTransformUsesActualClassNameAndIsIdempotentForBothLayouts() {
        for (Layout layout : Layout.values()) {
            byte[] original = contextFixture(layout);
            byte[] explicit = transformer.transform(original);
            ClassNode context = readClass(explicit);
            assertNativeHandleMethod(context, layout.handleField);
            assertNativeFieldReads(context, layout.handleField);
            assertContextLifecycle(context, layout.handleField);

            assertSame(explicit, transformer.transform(explicit));
            assertSame(explicit, transformer.transform(CONTEXT_GL, CONTEXT_GL, explicit));

            byte[] normal = transformer.transform(CONTEXT_GL, CONTEXT_GL, original);
            assertSame(normal, transformer.transform(normal));
        }
    }

    @Test
    void mixinEarlyRegistersExplicitLifecycleTransformerAndKeepsNormalEntry() throws Exception {
        ClassNode early;
        try (InputStream stream = LwjglContextLifecycleTransformerTest.class.getClassLoader()
            .getResourceAsStream("com/dhj/actinium/mixins/MixinEarly.class")) {
            assertNotNull(stream);
            early = readClass(stream.readAllBytes());
        }

        MethodNode getAsmTransformers = method(early, "getASMTransformerClass", "()[Ljava/lang/String;");
        Type lifecycleType = Type.getType(LwjglContextLifecycleTransformer.class);
        assertTrue(meaningfulInstructions(getAsmTransformers).stream().anyMatch(instruction ->
            instruction instanceof LdcInsnNode ldc && lifecycleType.equals(ldc.cst)));
        assertTrue(meaningfulInstructions(getAsmTransformers).stream().anyMatch(instruction ->
            instruction instanceof MethodInsnNode call
                && "registerExplicitLifecycleTransformer".equals(call.name)
                && "()V".equals(call.desc)));

        MethodNode registration = method(early, "registerExplicitLifecycleTransformer", "()V");
        assertEquals(Opcodes.ACC_SYNCHRONIZED, registration.access & Opcodes.ACC_SYNCHRONIZED);
        long registerCalls = early.methods.stream()
            .flatMap(method -> meaningfulInstructions(method).stream())
            .filter(instruction -> instruction instanceof MethodInsnNode call
                && "top/outlands/foundation/TransformerDelegate".equals(call.owner)
                && "registerExplicitTransformer".equals(call.name))
            .count();
        assertEquals(1, registerCalls);
        assertTrue(meaningfulInstructions(registration).stream().anyMatch(instruction ->
            instruction instanceof MethodInsnNode call
                && "top/outlands/foundation/TransformerDelegate".equals(call.owner)
                && "getExplicitTransformers".equals(call.name)));

        Set<String> targetNames = new HashSet<>();
        for (MethodNode method : early.methods) {
            for (AbstractInsnNode instruction : meaningfulInstructions(method)) {
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String target) {
                    targetNames.add(target);
                }
            }
        }
        assertTrue(targetNames.containsAll(LIFECYCLE_TARGETS));
    }

    @Test
    void rejectsMissingNativeHandleField() {
        for (Layout layout : Layout.values()) {
            byte[] fixture = rewriteClass(contextFixture(layout), node ->
                node.fields.removeIf(field -> field.name.equals(layout.handleField)));
            assertContextTransformFailure(fixture);
        }
    }

    @Test
    void rejectsAmbiguousNativeHandleFields() {
        byte[] fixture = rewriteClass(contextFixture(Layout.GLFW), node ->
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, Layout.SDL.handleField, "J", null, null)));
        assertContextTransformFailure(fixture);
    }

    @Test
    void rejectsNativeHandleMethodConflict() {
        byte[] fixture = rewriteClass(contextFixture(Layout.GLFW), node -> {
            node.interfaces.add(NATIVE_CONTEXT_HANDLE);
            MethodNode conflict = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                NATIVE_HANDLE_METHOD,
                "()J",
                null,
                null
            );
            conflict.instructions.add(new InsnNode(Opcodes.LCONST_0));
            conflict.instructions.add(new InsnNode(Opcodes.LRETURN));
            node.methods.add(conflict);
        });
        IllegalStateException failure = assertContextTransformFailure(fixture);
        assertTrue(failure.getMessage().contains("conflicting native handle method"), failure.getMessage());
    }

    @Test
    void rejectsNativeHandleMethodWithWrongField() {
        byte[] fixture = rewriteClass(transformedContext(Layout.GLFW), node -> {
            addWrongHandleField(node);
            MethodNode handleMethod = method(node, NATIVE_HANDLE_METHOD, "()J");
            FieldInsnNode handleRead = assertInstanceOf(
                FieldInsnNode.class,
                meaningfulInstructions(handleMethod).get(1)
            );
            handleRead.name = WRONG_HANDLE_FIELD;
        });
        assertContextTransformFailure(fixture);
    }

    @Test
    void rejectsMakeCurrentWithWrongNativeHandleField() {
        byte[] fixture = rewriteClass(transformedContext(Layout.GLFW), node ->
            useWrongCallbackHandleField(node, "makeCurrent", "bindIfNativeCurrent"));
        assertContextTransformFailure(fixture);
    }

    @Test
    void rejectsDestroyAbortWithWrongNativeHandleField() {
        byte[] fixture = rewriteClass(transformedContext(Layout.GLFW), node ->
            useWrongCallbackHandleField(node, "destroy", "abortBeforeContextDestroy"));
        assertContextTransformFailure(fixture);
    }

    @Test
    void rejectsDestroyRetireWithWrongNativeHandleField() {
        byte[] fixture = rewriteClass(transformedContext(Layout.GLFW), node ->
            useWrongCallbackHandleField(node, "destroy", "retireDestroyedContext"));
        assertContextTransformFailure(fixture);
    }

    @Test
    void rejectsDrawableSharedContextWithLegacyFieldRead() {
        byte[] fixture = rewriteClass(transformedDrawable(), node -> {
            MethodNode createSharedContext = method(node, "createSharedContext", "()" + CONTEXT_DESCRIPTOR);
            createSharedContext.instructions.set(
                sharedContextHandleCall(node),
                new FieldInsnNode(
                    Opcodes.GETFIELD,
                    CONTEXT_GL_INTERNAL,
                    Layout.GLFW.handleField,
                    "J"
                )
            );
        });
        assertDrawableTransformFailure(fixture);
    }

    @Test
    void rejectsDrawableSharedContextWithoutHandleCall() {
        byte[] fixture = rewriteClass(transformedDrawable(), node -> {
            MethodNode createSharedContext = method(node, "createSharedContext", "()" + CONTEXT_DESCRIPTOR);
            createSharedContext.instructions.remove(sharedContextHandleCall(node));
        });
        assertDrawableTransformFailure(fixture);
    }

    @Test
    void rejectsPartialNativeHandleInjection() {
        byte[] fixture = rewriteClass(contextFixture(Layout.GLFW), node ->
            node.interfaces.add(NATIVE_CONTEXT_HANDLE));
        assertContextTransformFailure(fixture);
    }

    @Test
    void rejectsPartialLifecycleInjection() {
        for (Layout layout : Layout.values()) {
            byte[] fixture = rewriteClass(contextFixture(layout), node -> {
                MethodNode makeCurrent = method(node, "makeCurrent", "()V");
                InsnList partial = new InsnList();
                partial.add(new VarInsnNode(Opcodes.ALOAD, 0));
                partial.add(new VarInsnNode(Opcodes.ALOAD, 0));
                partial.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    CONTEXT_GL_INTERNAL,
                    layout.handleField,
                    "J"
                ));
                partial.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    DISPLAY_BINDING,
                    "bindIfNativeCurrent",
                    "(Ljava/lang/Object;J)V",
                    false
                ));
                makeCurrent.instructions.insertBefore(
                    findReturn(makeCurrent, Opcodes.RETURN),
                    partial
                );
            });
            assertContextTransformFailure(fixture);
        }
    }

    @Test
    void selectsBackendFromLayoutAndRestoresGlfw() {
        try {
            transformer.transform(CONTEXT_GL, CONTEXT_GL, contextFixture(Layout.SDL));
            assertInstanceOf(SdlWindowBackend.class, WindowBackends.get());

            transformer.transform(CONTEXT_GL, CONTEXT_GL, contextFixture(Layout.GLFW));
            assertInstanceOf(GlfwWindowBackend.class, WindowBackends.get());

            transformer.transform(CONTEXT_GL, CONTEXT_GL, contextFixture(Layout.SDL));
            assertInstanceOf(SdlWindowBackend.class, WindowBackends.get());
        } finally {
            WindowBackends.useGlfw();
            assertInstanceOf(GlfwWindowBackend.class, WindowBackends.get());
        }
    }

    private FixtureSet fixtures(Layout layout) {
        return new FixtureSet(displayFixture(), drawableFixture(), contextFixture(layout));
    }

    private FixtureSet transformAll(FixtureSet fixture) {
        return new FixtureSet(
            transformer.transform(DISPLAY, DISPLAY, fixture.display()),
            transformer.transform(DRAWABLE_GL, DRAWABLE_GL, fixture.drawable()),
            transformer.transform(CONTEXT_GL, CONTEXT_GL, fixture.context())
        );
    }

    private byte[] transformedContext(Layout layout) {
        return transformer.transform(CONTEXT_GL, CONTEXT_GL, contextFixture(layout));
    }

    private byte[] transformedDrawable() {
        return transformer.transform(DRAWABLE_GL, DRAWABLE_GL, drawableFixture());
    }

    private IllegalStateException assertContextTransformFailure(byte[] fixture) {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> transformer.transform(CONTEXT_GL, CONTEXT_GL, fixture)
        );
        assertTrue(
            failure.getMessage().contains(CONTEXT_GL) || failure.getMessage().contains(CONTEXT_GL_INTERNAL),
            failure.getMessage()
        );
        return failure;
    }

    private void assertDrawableTransformFailure(byte[] fixture) {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> transformer.transform(DRAWABLE_GL, DRAWABLE_GL, fixture)
        );
        assertTrue(failure.getMessage().contains(DRAWABLE_GL), failure.getMessage());
    }

    private static void verifyLayout(FixtureSet fixture, Layout layout) {
        ClassNode context = readClass(fixture.context());
        assertNativeHandleMethod(context, layout.handleField);
        assertNativeFieldReads(context, layout.handleField);

        assertDisplayLifecycle(fixture.display());
        assertDrawableLifecycle(fixture.drawable());
        MethodNode createSharedContext = method(
            readClass(fixture.drawable()),
            "createSharedContext",
            "()" + CONTEXT_DESCRIPTOR
        );
        assertEquals(
            returnCount(createSharedContext, Opcodes.ARETURN),
            nativeHandleCallCount(createSharedContext)
        );
        assertContextLifecycle(context, layout.handleField);
    }

    private static void assertNativeHandleMethod(ClassNode context, String handleField) {
        assertEquals(1, context.interfaces.stream().filter(NATIVE_CONTEXT_HANDLE::equals).count());
        MethodNode handleMethod = method(context, NATIVE_HANDLE_METHOD, "()J");
        assertEquals(
            Opcodes.ACC_PUBLIC,
            handleMethod.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED
                | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)
        );

        List<AbstractInsnNode> instructions = meaningfulInstructions(handleMethod);
        assertEquals(3, instructions.size());
        assertEquals(Opcodes.ALOAD, instructions.get(0).getOpcode());
        assertEquals(Opcodes.LRETURN, instructions.get(2).getOpcode());
        FieldInsnNode handleRead = assertInstanceOf(FieldInsnNode.class, instructions.get(1));
        assertEquals(Opcodes.GETFIELD, handleRead.getOpcode());
        assertEquals(CONTEXT_GL_INTERNAL, handleRead.owner);
        assertEquals(handleField, handleRead.name);
        assertEquals("J", handleRead.desc);
    }

    private static void assertNativeFieldReads(ClassNode context, String handleField) {
        for (MethodNode method : context.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode field
                    && CONTEXT_GL_INTERNAL.equals(field.owner)
                    && (Layout.GLFW.handleField.equals(field.name) || Layout.SDL.handleField.equals(field.name))) {
                    assertEquals(handleField, field.name, "Unexpected native field read in " + method.name);
                }
            }
        }
    }

    private static void assertDisplayLifecycle(byte[] displayBytes) {
        ClassNode display = readClass(displayBytes);
        assertReturnCallbacks(display, "create", "bindDisplayContextIfNativeCurrent",
            "(Lorg/lwjgl/opengl/DrawableGL;)V", Opcodes.RETURN);
        assertReturnCallbacks(display, "makeCurrent", "bindDisplayContextIfNativeCurrent",
            "(Lorg/lwjgl/opengl/DrawableGL;)V", Opcodes.RETURN);
        assertReturnCallbacks(display, "releaseContext", "releaseIfNativeUnbound", "()V", Opcodes.RETURN);

        MethodNode destroy = method(display, "destroy", "()V");
        assertEquals(1, callbackCount(destroy, "captureDisplayContextBeforeDestroy",
            "(Lorg/lwjgl/opengl/DrawableGL;Z)Lorg/lwjgl/opengl/ContextGL;"));
        assertReturnCallbacks(display, "destroy", "retireDestroyedDisplayContext", "(Ljava/lang/Object;)V",
            Opcodes.RETURN);
        assertEquals(List.of("capture", "invalidate", "retire"), displayDestroyEvents(destroy));
    }

    private static void assertDrawableLifecycle(byte[] drawableBytes) {
        ClassNode drawable = readClass(drawableBytes);
        MethodNode createSharedContext = method(drawable, "createSharedContext", "()" + CONTEXT_DESCRIPTOR);
        int returns = returnCount(createSharedContext, Opcodes.ARETURN);
        assertEquals(returns, callbackCount(createSharedContext, "registerSharedContext",
            "(Ljava/lang/Object;JLjava/lang/Object;)V"));
    }

    private static void assertContextLifecycle(ClassNode context, String handleField) {
        assertReturnCallbacks(context, "makeCurrent", "bindIfNativeCurrent", "(Ljava/lang/Object;J)V",
            Opcodes.RETURN);
        assertReturnCallbacks(context, "releaseCurrent", "releaseIfNativeUnbound", "()V", Opcodes.RETURN);
        assertEquals(returnCount(method(context, "makeCurrent", "()V"), Opcodes.RETURN),
            fieldReadCount(method(context, "makeCurrent", "()V"), handleField));

        MethodNode destroy = method(context, "destroy", "()V");
        int returns = returnCount(destroy, Opcodes.RETURN);
        assertEquals(1, callbackCount(destroy, "abortBeforeContextDestroy", "(Ljava/lang/Object;ZJ)V"));
        assertEquals(returns, callbackCount(destroy, "retireDestroyedContext", "(Ljava/lang/Object;J)V"));
        assertEquals(1 + returns, fieldReadCount(destroy, handleField));
        assertEquals(List.of("abort", "invalidate", "retire"), contextDestroyEvents(destroy, handleField));
    }

    private static void assertReturnCallbacks(
        ClassNode owner,
        String methodName,
        String callbackName,
        String callbackDescriptor,
        int returnOpcode
    ) {
        MethodNode method = method(owner, methodName, "()V");
        assertEquals(returnCount(method, returnOpcode), callbackCount(method, callbackName, callbackDescriptor));
    }

    private static int callbackCount(MethodNode method, String callbackName, String callbackDescriptor) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && DISPLAY_BINDING.equals(call.owner)
                && callbackName.equals(call.name)
                && callbackDescriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int fieldReadCount(MethodNode method, String fieldName) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD
                && CONTEXT_GL_INTERNAL.equals(field.owner)
                && fieldName.equals(field.name)
                && "J".equals(field.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int nativeHandleCallCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && CONTEXT_GL_INTERNAL.equals(call.owner)
                && NATIVE_HANDLE_METHOD.equals(call.name)
                && "()J".equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int returnCount(MethodNode method, int returnOpcode) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() == returnOpcode) {
                count++;
            }
        }
        return count;
    }

    private static List<String> displayDestroyEvents(MethodNode destroy) {
        List<String> events = new ArrayList<>();
        for (AbstractInsnNode instruction : meaningfulInstructions(destroy)) {
            if (instruction instanceof MethodInsnNode call && DISPLAY_BINDING.equals(call.owner)) {
                events.add(switch (call.name) {
                    case "captureDisplayContextBeforeDestroy" -> "capture";
                    case "retireDestroyedDisplayContext" -> "retire";
                    default -> call.name;
                });
            } else if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.PUTSTATIC
                && "displayCreated".equals(field.name)) {
                events.add("invalidate");
            }
        }
        return events;
    }

    private static List<String> contextDestroyEvents(MethodNode destroy, String handleField) {
        List<String> events = new ArrayList<>();
        for (AbstractInsnNode instruction : meaningfulInstructions(destroy)) {
            if (instruction instanceof MethodInsnNode call && DISPLAY_BINDING.equals(call.owner)) {
                events.add(switch (call.name) {
                    case "abortBeforeContextDestroy" -> "abort";
                    case "retireDestroyedContext" -> "retire";
                    default -> call.name;
                });
            } else if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.PUTFIELD
                && handleField.equals(field.name)) {
                events.add("invalidate");
            }
        }
        return events;
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

    private static AbstractInsnNode findReturn(MethodNode method, int returnOpcode) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() == returnOpcode) {
                return instruction;
            }
        }
        throw new AssertionError("Missing return in " + method.name + method.desc);
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing method " + name + descriptor));
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void addWrongHandleField(ClassNode node) {
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, WRONG_HANDLE_FIELD, "J", null, null));
    }

    private static void useWrongCallbackHandleField(
        ClassNode node,
        String methodName,
        String callbackName
    ) {
        addWrongHandleField(node);
        MethodNode method = method(node, methodName, "()V");
        List<AbstractInsnNode> instructions = meaningfulInstructions(method);
        for (int index = 1; index < instructions.size(); index++) {
            if (instructions.get(index) instanceof MethodInsnNode call
                && DISPLAY_BINDING.equals(call.owner)
                && callbackName.equals(call.name)) {
                FieldInsnNode handleRead = assertInstanceOf(
                    FieldInsnNode.class,
                    instructions.get(index - 1)
                );
                assertEquals(Opcodes.GETFIELD, handleRead.getOpcode());
                handleRead.name = WRONG_HANDLE_FIELD;
                return;
            }
        }
        throw new AssertionError("Missing callback " + callbackName + " in " + methodName);
    }

    private static MethodInsnNode sharedContextHandleCall(ClassNode node) {
        MethodNode createSharedContext = method(node, "createSharedContext", "()" + CONTEXT_DESCRIPTOR);
        for (AbstractInsnNode instruction : meaningfulInstructions(createSharedContext)) {
            if (instruction instanceof MethodInsnNode call
                && CONTEXT_GL_INTERNAL.equals(call.owner)
                && NATIVE_HANDLE_METHOD.equals(call.name)
                && "()J".equals(call.desc)) {
                return call;
            }
        }
        throw new AssertionError("Missing shared-context native handle call");
    }

    private static byte[] rewriteClass(byte[] bytes, Consumer<ClassNode> edit) {
        ClassNode node = readClass(bytes);
        edit.accept(node);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] displayFixture() {
        ClassWriter writer = newWriter(DISPLAY_INTERNAL);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "drawable", DRAWABLE_DESCRIPTOR,
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "displayCreated", "Z",
            null, null).visitEnd();
        addConstructor(writer);
        addLongMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getWindow");
        addEmptyMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", "()V");
        addEmptyMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "makeCurrent", "()V");
        addEmptyMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "releaseContext", "()V");

        var destroy = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "destroy", "()V",
            null, null);
        destroy.visitCode();
        destroy.visitInsn(Opcodes.ICONST_0);
        destroy.visitFieldInsn(Opcodes.PUTSTATIC, DISPLAY_INTERNAL, "displayCreated", "Z");
        destroy.visitInsn(Opcodes.RETURN);
        destroy.visitMaxs(0, 0);
        destroy.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] drawableFixture() {
        ClassWriter writer = newWriter(DRAWABLE_GL_INTERNAL);
        writer.visitField(Opcodes.ACC_PUBLIC, "context", CONTEXT_DESCRIPTOR, null, null).visitEnd();
        addConstructor(writer);

        var getContext = writer.visitMethod(Opcodes.ACC_PUBLIC, "getContext", "()" + CONTEXT_DESCRIPTOR,
            null, null);
        getContext.visitCode();
        getContext.visitVarInsn(Opcodes.ALOAD, 0);
        getContext.visitFieldInsn(Opcodes.GETFIELD, DRAWABLE_GL_INTERNAL, "context", CONTEXT_DESCRIPTOR);
        getContext.visitInsn(Opcodes.ARETURN);
        getContext.visitMaxs(0, 0);
        getContext.visitEnd();

        var createSharedContext = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "createSharedContext",
            "()" + CONTEXT_DESCRIPTOR,
            null,
            null
        );
        createSharedContext.visitCode();
        createSharedContext.visitTypeInsn(Opcodes.NEW, CONTEXT_GL_INTERNAL);
        createSharedContext.visitInsn(Opcodes.DUP);
        createSharedContext.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTEXT_GL_INTERNAL, "<init>", "()V", false);
        createSharedContext.visitInsn(Opcodes.ARETURN);
        createSharedContext.visitMaxs(0, 0);
        createSharedContext.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] contextFixture(Layout layout) {
        ClassWriter writer = newWriter(CONTEXT_GL_INTERNAL);
        if (layout == Layout.SDL) {
            writer.visitField(Opcodes.ACC_PUBLIC, "sdlWindow", "J", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PUBLIC, layout.handleField, "J", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "shared", "Z", null, null).visitEnd();
        addConstructor(writer);
        addEmptyMethod(writer, Opcodes.ACC_PUBLIC, "makeCurrent", "()V");
        addEmptyMethod(writer, Opcodes.ACC_PUBLIC, "releaseCurrent", "()V");

        var destroy = writer.visitMethod(Opcodes.ACC_PUBLIC, "destroy", "()V", null, null);
        destroy.visitCode();
        destroy.visitVarInsn(Opcodes.ALOAD, 0);
        destroy.visitInsn(Opcodes.LCONST_0);
        destroy.visitFieldInsn(Opcodes.PUTFIELD, CONTEXT_GL_INTERNAL, layout.handleField, "J");
        destroy.visitInsn(Opcodes.RETURN);
        destroy.visitMaxs(0, 0);
        destroy.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter newWriter(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        return writer;
    }

    private static void addConstructor(ClassWriter writer) {
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static void addEmptyMethod(ClassWriter writer, int access, String name, String descriptor) {
        var method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void addLongMethod(ClassWriter writer, int access, String name) {
        var method = writer.visitMethod(access, name, "()J", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.LCONST_0);
        method.visitInsn(Opcodes.LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private enum Layout {
        GLFW("glfwWindow"),
        SDL("sdlContext");

        private final String handleField;

        Layout(String handleField) {
            this.handleField = handleField;
        }
    }

    private record FixtureSet(byte[] display, byte[] drawable, byte[] context) {
    }
}
