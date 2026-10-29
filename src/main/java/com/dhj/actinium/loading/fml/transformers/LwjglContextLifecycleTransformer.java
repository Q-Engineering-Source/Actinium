package com.dhj.actinium.loading.fml.transformers;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Injects native context lifetime callbacks after Cleanroom synthesizes the legacy LWJGL class names. */
public final class LwjglContextLifecycleTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    private static final String DISPLAY = "org.lwjgl.opengl.Display";
    private static final String DRAWABLE_GL = "org.lwjgl.opengl.DrawableGL";
    private static final String CONTEXT_GL = "org.lwjgl.opengl.ContextGL";
    private static final String DISPLAY_BINDING = "com/dhj/actinium/compat/lwjgl/DisplayContextBinding";

    private static final String DRAWABLE_DESCRIPTOR = "Lorg/lwjgl/opengl/DrawableGL;";
    private static final String CONTEXT_DESCRIPTOR = "Lorg/lwjgl/opengl/ContextGL;";

    private static final String BIND_DISPLAY_DESCRIPTOR = "(Lorg/lwjgl/opengl/DrawableGL;)V";
    private static final String CAPTURE_DISPLAY_DESTROY_DESCRIPTOR =
        "(Lorg/lwjgl/opengl/DrawableGL;Z)Lorg/lwjgl/opengl/ContextGL;";
    private static final String RETIRE_DISPLAY_DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final String BIND_CONTEXT_DESCRIPTOR = "(Ljava/lang/Object;J)V";
    private static final String ABORT_CONTEXT_DESTROY_DESCRIPTOR = "(Ljava/lang/Object;ZJ)V";
    private static final String RETIRE_CONTEXT_DESCRIPTOR = "(Ljava/lang/Object;J)V";
    private static final String REGISTER_SHARED_CONTEXT_DESCRIPTOR = "(Ljava/lang/Object;JLjava/lang/Object;)V";

    /** The legacy name is produced by LWJGLTransformer, so a missing class here means the loader contract failed. */
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        final String targetName = targetName(name, transformedName);
        if (targetName == null) {
            return basicClass;
        }

        try {
            if (basicClass == null) {
                throw transformationFailure(
                    targetName,
                    "LWJGLTransformer did not supply the synthesized legacy class bytes"
                );
            }
            final ClassReader reader = new ClassReader(basicClass);
            final ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.EXPAND_FRAMES);
            if (!targetName.replace('.', '/').equals(classNode.name)) {
                throw transformationFailure(
                    targetName,
                    "expected class node " + targetName.replace('.', '/') + " but got " + classNode.name
                );
            }

            final boolean changed = switch (targetName) {
                case DISPLAY -> transformDisplay(classNode);
                case DRAWABLE_GL -> transformDrawable(classNode);
                case CONTEXT_GL -> transformContext(classNode);
                default -> throw transformationFailure(targetName, "unsupported lifecycle target");
            };
            if (!changed) {
                return basicClass;
            }

            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to inject Cleanroom context lifecycle callbacks into {}", targetName, failure);
            throw failure;
        }
    }

    private static String targetName(String name, String transformedName) {
        if (isLegacyTarget(name)) {
            return name;
        }
        if (isLegacyTarget(transformedName)) {
            return transformedName;
        }
        return null;
    }

    private static boolean isLegacyTarget(String name) {
        return DISPLAY.equals(name) || DRAWABLE_GL.equals(name) || CONTEXT_GL.equals(name);
    }

    private static boolean transformDisplay(ClassNode classNode) {
        requireField(classNode, "drawable", DRAWABLE_DESCRIPTOR, true);
        requireField(classNode, "displayCreated", "Z", true);
        requireMethod(classNode, "getWindow", "()J", true);
        final MethodNode create = requireMethod(classNode, "create", "()V", true);
        final MethodNode makeCurrent = requireMethod(classNode, "makeCurrent", "()V", true);
        final MethodNode releaseContext = requireMethod(classNode, "releaseContext", "()V", true);
        final MethodNode destroy = requireMethod(classNode, "destroy", "()V", true);

        final int createReturns = returnCount(classNode.name, create, Opcodes.RETURN);
        final int makeCurrentReturns = returnCount(classNode.name, makeCurrent, Opcodes.RETURN);
        final int releaseReturns = returnCount(classNode.name, releaseContext, Opcodes.RETURN);
        final int destroyReturns = returnCount(classNode.name, destroy, Opcodes.RETURN);
        final int captureCalls = callCount(destroy, "captureDisplayContextBeforeDestroy", CAPTURE_DISPLAY_DESTROY_DESCRIPTOR);
        final int retireCalls = callCount(destroy, "retireDestroyedDisplayContext", RETIRE_DISPLAY_DESCRIPTOR);
        final int totalCalls = callCount(create, "bindDisplayContextIfNativeCurrent", BIND_DISPLAY_DESCRIPTOR)
            + callCount(makeCurrent, "bindDisplayContextIfNativeCurrent", BIND_DISPLAY_DESCRIPTOR)
            + callCount(releaseContext, "releaseIfNativeUnbound", "()V")
            + captureCalls + retireCalls;
        if (totalCalls != 0) {
            if (callCount(create, "bindDisplayContextIfNativeCurrent", BIND_DISPLAY_DESCRIPTOR) == createReturns
                && callCount(makeCurrent, "bindDisplayContextIfNativeCurrent", BIND_DISPLAY_DESCRIPTOR) == makeCurrentReturns
                && callCount(releaseContext, "releaseIfNativeUnbound", "()V") == releaseReturns
                && captureCalls == 1
                && retireCalls == destroyReturns) {
                return false;
            }
            throw transformationFailure(DISPLAY, "found a partial or duplicate lifecycle injection");
        }

        final int contextLocal = destroy.maxLocals;
        if (contextLocal < 0 || contextLocal == Integer.MAX_VALUE) {
            throw transformationFailure(DISPLAY, "destroy()V has no available local slot for its context token");
        }

        injectBeforeReturns(DISPLAY, create, Opcodes.RETURN, LwjglContextLifecycleTransformer::bindDisplayContext);
        injectBeforeReturns(DISPLAY, makeCurrent, Opcodes.RETURN, LwjglContextLifecycleTransformer::bindDisplayContext);
        injectBeforeReturns(DISPLAY, releaseContext, Opcodes.RETURN, LwjglContextLifecycleTransformer::releaseContext);

        destroy.maxLocals = contextLocal + 1;
        destroy.instructions.insert(displayDestroyCapture(contextLocal));
        injectBeforeReturns(DISPLAY, destroy, Opcodes.RETURN, () -> retireDisplayContext(contextLocal));
        return true;
    }

    private static boolean transformDrawable(ClassNode classNode) {
        requireField(classNode, "context", CONTEXT_DESCRIPTOR, false);
        requireMethod(classNode, "getContext", "()" + CONTEXT_DESCRIPTOR, false);
        final MethodNode createSharedContext = requireMethod(
            classNode,
            "createSharedContext",
            "()" + CONTEXT_DESCRIPTOR,
            false
        );
        final int sharedContextReturns = returnCount(classNode.name, createSharedContext, Opcodes.ARETURN);
        final int registerCalls = callCount(
            createSharedContext,
            "registerSharedContext",
            REGISTER_SHARED_CONTEXT_DESCRIPTOR
        );
        if (registerCalls != 0) {
            if (registerCalls == sharedContextReturns) {
                return false;
            }
            throw transformationFailure(DRAWABLE_GL, "found a partial or duplicate shared-context injection");
        }

        injectBeforeReturns(
            DRAWABLE_GL,
            createSharedContext,
            Opcodes.ARETURN,
            LwjglContextLifecycleTransformer::registerSharedContext
        );
        return true;
    }

    private static boolean transformContext(ClassNode classNode) {
        requireField(classNode, "glfwWindow", "J", false);
        requireField(classNode, "shared", "Z", false);
        final MethodNode makeCurrent = requireMethod(classNode, "makeCurrent", "()V", false);
        final MethodNode releaseCurrent = requireMethod(classNode, "releaseCurrent", "()V", false);
        final MethodNode destroy = requireMethod(classNode, "destroy", "()V", false);

        final int makeCurrentReturns = returnCount(classNode.name, makeCurrent, Opcodes.RETURN);
        final int releaseReturns = returnCount(classNode.name, releaseCurrent, Opcodes.RETURN);
        final int destroyReturns = returnCount(classNode.name, destroy, Opcodes.RETURN);
        final int abortCalls = callCount(destroy, "abortBeforeContextDestroy", ABORT_CONTEXT_DESTROY_DESCRIPTOR);
        final int retireCalls = callCount(destroy, "retireDestroyedContext", RETIRE_CONTEXT_DESCRIPTOR);
        final int totalCalls = callCount(makeCurrent, "bindIfNativeCurrent", BIND_CONTEXT_DESCRIPTOR)
            + callCount(releaseCurrent, "releaseIfNativeUnbound", "()V")
            + abortCalls + retireCalls;
        if (totalCalls != 0) {
            if (callCount(makeCurrent, "bindIfNativeCurrent", BIND_CONTEXT_DESCRIPTOR) == makeCurrentReturns
                && callCount(releaseCurrent, "releaseIfNativeUnbound", "()V") == releaseReturns
                && abortCalls == 1
                && retireCalls == destroyReturns) {
                return false;
            }
            throw transformationFailure(CONTEXT_GL, "found a partial or duplicate lifecycle injection");
        }

        injectBeforeReturns(CONTEXT_GL, makeCurrent, Opcodes.RETURN, LwjglContextLifecycleTransformer::bindContext);
        injectBeforeReturns(CONTEXT_GL, releaseCurrent, Opcodes.RETURN, LwjglContextLifecycleTransformer::releaseContext);
        destroy.instructions.insert(contextDestroyAbort());
        injectBeforeReturns(CONTEXT_GL, destroy, Opcodes.RETURN, LwjglContextLifecycleTransformer::retireContext);
        return true;
    }

    private static void requireField(ClassNode classNode, String name, String descriptor, boolean isStatic) {
        final List<FieldNode> fields = classNode.fields.stream()
            .filter(field -> name.equals(field.name) && descriptor.equals(field.desc))
            .toList();
        if (fields.size() != 1) {
            throw transformationFailure(classNode.name, "expected one field " + name + descriptor + ", found " + fields.size());
        }
        final FieldNode field = fields.getFirst();
        final boolean fieldIsStatic = (field.access & Opcodes.ACC_STATIC) != 0;
        if (fieldIsStatic != isStatic) {
            throw transformationFailure(classNode.name, "field " + name + descriptor + " has an unexpected static modifier");
        }
    }

    private static MethodNode requireMethod(ClassNode classNode, String name, String descriptor, boolean isStatic) {
        final List<MethodNode> methods = classNode.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
            .toList();
        if (methods.size() != 1) {
            throw transformationFailure(classNode.name, "expected one method " + name + descriptor + ", found " + methods.size());
        }
        final MethodNode method = methods.getFirst();
        final boolean methodIsStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        if (methodIsStatic != isStatic) {
            throw transformationFailure(classNode.name, "method " + name + descriptor + " has an unexpected static modifier");
        }
        return method;
    }

    private static int returnCount(String className, MethodNode method, int returnOpcode) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == returnOpcode) {
                count++;
            }
        }
        if (count == 0) {
            throw transformationFailure(className, "method " + method.name + method.desc + " has no expected return point");
        }
        return count;
    }

    private static int callCount(MethodNode method, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && DISPLAY_BINDING.equals(call.owner)
                && name.equals(call.name)
                && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static void injectBeforeReturns(String className, MethodNode method, int returnOpcode, InstructionFactory factory) {
        boolean injected = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            final AbstractInsnNode next = instruction.getNext();
            if (instruction.getOpcode() == returnOpcode) {
                method.instructions.insertBefore(instruction, factory.create());
                injected = true;
            }
            instruction = next;
        }
        if (!injected) {
            throw transformationFailure(className, "method " + method.name + method.desc + " has no return point to inject");
        }
    }

    private static InsnList bindDisplayContext() {
        final InsnList instructions = new InsnList();
        instructions.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            "org/lwjgl/opengl/Display",
            "drawable",
            DRAWABLE_DESCRIPTOR
        ));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "bindDisplayContextIfNativeCurrent",
            BIND_DISPLAY_DESCRIPTOR,
            false
        ));
        return instructions;
    }

    private static InsnList displayDestroyCapture(int contextLocal) {
        final InsnList instructions = new InsnList();
        instructions.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            "org/lwjgl/opengl/Display",
            "drawable",
            DRAWABLE_DESCRIPTOR
        ));
        instructions.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            "org/lwjgl/opengl/Display",
            "displayCreated",
            "Z"
        ));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "captureDisplayContextBeforeDestroy",
            CAPTURE_DISPLAY_DESTROY_DESCRIPTOR,
            false
        ));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, contextLocal));
        return instructions;
    }

    private static InsnList retireDisplayContext(int contextLocal) {
        final InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, contextLocal));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "retireDestroyedDisplayContext",
            RETIRE_DISPLAY_DESCRIPTOR,
            false
        ));
        return instructions;
    }

    private static InsnList releaseContext() {
        final InsnList instructions = new InsnList();
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "releaseIfNativeUnbound",
            "()V",
            false
        ));
        return instructions;
    }

    private static InsnList registerSharedContext() {
        final InsnList instructions = new InsnList();
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            "glfwWindow",
            "J"
        ));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/DrawableGL",
            "context",
            CONTEXT_DESCRIPTOR
        ));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "registerSharedContext",
            REGISTER_SHARED_CONTEXT_DESCRIPTOR,
            false
        ));
        return instructions;
    }

    private static InsnList bindContext() {
        final InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            "glfwWindow",
            "J"
        ));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "bindIfNativeCurrent",
            BIND_CONTEXT_DESCRIPTOR,
            false
        ));
        return instructions;
    }

    private static InsnList contextDestroyAbort() {
        final InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            "shared",
            "Z"
        ));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            "glfwWindow",
            "J"
        ));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "abortBeforeContextDestroy",
            ABORT_CONTEXT_DESTROY_DESCRIPTOR,
            false
        ));
        return instructions;
    }

    private static InsnList retireContext() {
        final InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            "glfwWindow",
            "J"
        ));
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            DISPLAY_BINDING,
            "retireDestroyedContext",
            RETIRE_CONTEXT_DESCRIPTOR,
            false
        ));
        return instructions;
    }

    private static IllegalStateException transformationFailure(String targetName, String reason) {
        return new IllegalStateException("Cannot transform Cleanroom context target " + targetName + ": " + reason);
    }

    @FunctionalInterface
    private interface InstructionFactory {
        InsnList create();
    }
}
