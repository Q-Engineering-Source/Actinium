package com.dhj.actinium.loading.fml.transformers;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
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
import top.outlands.foundation.IExplicitTransformer;

import java.util.List;

/** Injects native context lifetime callbacks for supported LWJGL display implementations. */
public final class LwjglContextLifecycleTransformer implements IClassTransformer, IExplicitTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    private static final String DISPLAY = "org.lwjgl.opengl.Display";
    private static final String DRAWABLE_GL = "org.lwjgl.opengl.DrawableGL";
    private static final String CONTEXT_GL = "org.lwjgl.opengl.ContextGL";
    private static final String DISPLAY_BINDING = "com/dhj/actinium/compat/lwjgl/DisplayContextBinding";
    private static final String NATIVE_CONTEXT_HANDLE = "com/gtnewhorizons/angelica/glsm/context/NativeContextHandle";
    private static final String NATIVE_HANDLE_METHOD = "actinium$nativeHandle";
    private static final String NATIVE_HANDLE_DESCRIPTOR = "()J";
    private static final String GLFW_WINDOW_FIELD = "glfwWindow";
    private static final String SDL_CONTEXT_FIELD = "sdlContext";

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

    @Override
    public int getPriority() {
        return IClassTransformer.super.getPriority();
    }

    @Override
    public byte[] transform(byte[] basicClass) {
        final String className = new ClassReader(basicClass).getClassName().replace('/', '.');
        return transform(className, className, basicClass);
    }

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
            LOGGER.error("Failed to inject LWJGL context lifecycle callbacks into {}", targetName, failure);
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
        final int handleCalls = nativeHandleCallCount(createSharedContext);
        if (registerCalls != 0 || handleCalls != 0) {
            if (registerCalls == sharedContextReturns
                && handleCalls == sharedContextReturns
                && hasCompleteSharedContextInjections(createSharedContext)) {
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
        final FieldNode nativeHandle = nativeHandleField(classNode);
        requireField(classNode, "shared", "Z", false);
        final MethodNode makeCurrent = requireMethod(classNode, "makeCurrent", "()V", false);
        final MethodNode releaseCurrent = requireMethod(classNode, "releaseCurrent", "()V", false);
        final MethodNode destroy = requireMethod(classNode, "destroy", "()V", false);
        final boolean nativeHandleInjected = nativeHandleInjectionPresent(classNode, nativeHandle);

        final int makeCurrentReturns = returnCount(classNode.name, makeCurrent, Opcodes.RETURN);
        final int releaseReturns = returnCount(classNode.name, releaseCurrent, Opcodes.RETURN);
        final int destroyReturns = returnCount(classNode.name, destroy, Opcodes.RETURN);
        final int abortCalls = callCount(destroy, "abortBeforeContextDestroy", ABORT_CONTEXT_DESTROY_DESCRIPTOR);
        final int retireCalls = callCount(destroy, "retireDestroyedContext", RETIRE_CONTEXT_DESCRIPTOR);
        final int totalCalls = callCount(makeCurrent, "bindIfNativeCurrent", BIND_CONTEXT_DESCRIPTOR)
            + callCount(releaseCurrent, "releaseIfNativeUnbound", "()V")
            + abortCalls + retireCalls;
        if (totalCalls != 0) {
            if (nativeHandleInjected
                && callCount(makeCurrent, "bindIfNativeCurrent", BIND_CONTEXT_DESCRIPTOR) == makeCurrentReturns
                && callCount(releaseCurrent, "releaseIfNativeUnbound", "()V") == releaseReturns
                && abortCalls == 1
                && retireCalls == destroyReturns
                && hasNativeHandleCallbacksBeforeReturns(
                    makeCurrent,
                    "bindIfNativeCurrent",
                    BIND_CONTEXT_DESCRIPTOR,
                    nativeHandle.name
                )
                && hasNativeHandleArgument(
                    findBindingCall(destroy, "abortBeforeContextDestroy", ABORT_CONTEXT_DESTROY_DESCRIPTOR),
                    nativeHandle.name
                )
                && hasNativeHandleCallbacksBeforeReturns(
                    destroy,
                    "retireDestroyedContext",
                    RETIRE_CONTEXT_DESCRIPTOR,
                    nativeHandle.name
                )) {
                selectWindowBackend(nativeHandle);
                return false;
            }
            throw transformationFailure(CONTEXT_GL, "found a partial or duplicate lifecycle injection");
        }
        if (nativeHandleInjected) {
            throw transformationFailure(CONTEXT_GL, "found a partial native handle injection");
        }

        injectNativeHandle(classNode, nativeHandle);
        injectBeforeReturns(CONTEXT_GL, makeCurrent, Opcodes.RETURN, () -> bindContext(nativeHandle.name));
        injectBeforeReturns(CONTEXT_GL, releaseCurrent, Opcodes.RETURN, LwjglContextLifecycleTransformer::releaseContext);
        destroy.instructions.insert(contextDestroyAbort(nativeHandle.name));
        injectBeforeReturns(CONTEXT_GL, destroy, Opcodes.RETURN, () -> retireContext(nativeHandle.name));
        selectWindowBackend(nativeHandle);
        return true;
    }

    private static FieldNode nativeHandleField(ClassNode classNode) {
        final boolean hasGlfw = classNode.fields.stream().anyMatch(field -> GLFW_WINDOW_FIELD.equals(field.name));
        final boolean hasSdl = classNode.fields.stream().anyMatch(field -> SDL_CONTEXT_FIELD.equals(field.name));
        if (hasGlfw == hasSdl) {
            throw transformationFailure(
                classNode.name,
                "expected exactly one field glfwWindowJ or sdlContextJ"
            );
        }
        return requireField(classNode, hasSdl ? SDL_CONTEXT_FIELD : GLFW_WINDOW_FIELD, "J", false);
    }

    private static boolean nativeHandleInjectionPresent(ClassNode classNode, FieldNode nativeHandle) {
        final long interfaceCount = classNode.interfaces.stream().filter(NATIVE_CONTEXT_HANDLE::equals).count();
        final List<MethodNode> handleMethods = classNode.methods.stream()
            .filter(method -> NATIVE_HANDLE_METHOD.equals(method.name))
            .toList();
        if (interfaceCount == 0 && handleMethods.isEmpty()) {
            return false;
        }
        if (interfaceCount != 1 || handleMethods.size() != 1) {
            throw transformationFailure(CONTEXT_GL, "found a partial or conflicting native handle injection");
        }

        final MethodNode handleMethod = handleMethods.getFirst();
        final int invalidAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
            | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
        if (!NATIVE_HANDLE_DESCRIPTOR.equals(handleMethod.desc)
            || (handleMethod.access & Opcodes.ACC_PUBLIC) == 0
            || (handleMethod.access & invalidAccess) != 0
            || !hasExpectedNativeHandleBody(handleMethod, classNode.name, nativeHandle)) {
            throw transformationFailure(CONTEXT_GL, "found a conflicting native handle method");
        }
        return true;
    }

    private static boolean hasCompleteSharedContextInjections(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }

            final AbstractInsnNode registerCall = previousInstruction(instruction);
            final AbstractInsnNode parentRead = previousInstruction(registerCall);
            final AbstractInsnNode parentLoad = previousInstruction(parentRead);
            final AbstractInsnNode handleCall = previousInstruction(parentLoad);
            if (!isBindingCall(registerCall, "registerSharedContext", REGISTER_SHARED_CONTEXT_DESCRIPTOR)
                || !isField(
                    parentRead,
                    Opcodes.GETFIELD,
                    "org/lwjgl/opengl/DrawableGL",
                    "context",
                    CONTEXT_DESCRIPTOR
                )
                || !isVar(parentLoad, Opcodes.ALOAD, 0)
                || !isNativeHandleCall(handleCall)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNativeHandleCallbacksBeforeReturns(
        MethodNode method,
        String callbackName,
        String callbackDescriptor,
        String nativeHandleField
    ) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                final AbstractInsnNode callback = previousInstruction(instruction);
                if (!isBindingCall(callback, callbackName, callbackDescriptor)
                    || !hasNativeHandleArgument((MethodInsnNode) callback, nativeHandleField)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasNativeHandleArgument(MethodInsnNode callback, String nativeHandleField) {
        final AbstractInsnNode handleRead = previousInstruction(callback);
        final AbstractInsnNode handleLoad = previousInstruction(handleRead);
        return isField(handleRead, Opcodes.GETFIELD, "org/lwjgl/opengl/ContextGL", nativeHandleField, "J")
            && isVar(handleLoad, Opcodes.ALOAD, 0);
    }

    private static MethodInsnNode findBindingCall(MethodNode method, String name, String descriptor) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (isBindingCall(instruction, name, descriptor)) {
                return (MethodInsnNode) instruction;
            }
        }
        return null;
    }

    private static boolean isBindingCall(AbstractInsnNode instruction, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call
            && call.getOpcode() == Opcodes.INVOKESTATIC
            && DISPLAY_BINDING.equals(call.owner)
            && name.equals(call.name)
            && descriptor.equals(call.desc);
    }

    private static int nativeHandleCallCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (isNativeHandleCall(instruction)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isNativeHandleCall(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "org/lwjgl/opengl/ContextGL".equals(call.owner)
            && NATIVE_HANDLE_METHOD.equals(call.name)
            && NATIVE_HANDLE_DESCRIPTOR.equals(call.desc);
    }

    private static boolean hasExpectedNativeHandleBody(
        MethodNode method,
        String owner,
        FieldNode nativeHandle
    ) {
        final AbstractInsnNode load = firstInstruction(method);
        final AbstractInsnNode read = nextInstruction(load);
        final AbstractInsnNode returnInstruction = nextInstruction(read);
        return isVar(load, Opcodes.ALOAD, 0)
            && isField(read, Opcodes.GETFIELD, owner, nativeHandle.name, nativeHandle.desc)
            && returnInstruction != null
            && returnInstruction.getOpcode() == Opcodes.LRETURN
            && nextInstruction(returnInstruction) == null;
    }

    private static boolean isField(
        AbstractInsnNode instruction,
        int opcode,
        String owner,
        String name,
        String descriptor
    ) {
        return instruction instanceof FieldInsnNode field
            && field.getOpcode() == opcode
            && owner.equals(field.owner)
            && name.equals(field.name)
            && descriptor.equals(field.desc);
    }

    private static boolean isVar(AbstractInsnNode instruction, int opcode, int index) {
        return instruction instanceof VarInsnNode variable
            && variable.getOpcode() == opcode
            && variable.var == index;
    }

    private static AbstractInsnNode firstInstruction(MethodNode method) {
        final AbstractInsnNode first = method.instructions.getFirst();
        return first == null || first.getOpcode() >= 0 ? first : nextInstruction(first);
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        do {
            instruction = instruction.getNext();
        } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        do {
            instruction = instruction.getPrevious();
        } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static void injectNativeHandle(ClassNode classNode, FieldNode nativeHandle) {
        classNode.interfaces.add(NATIVE_CONTEXT_HANDLE);
        final MethodNode handleMethod = new MethodNode(
            Opcodes.ACC_PUBLIC,
            NATIVE_HANDLE_METHOD,
            NATIVE_HANDLE_DESCRIPTOR,
            null,
            null
        );
        handleMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        handleMethod.instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            classNode.name,
            nativeHandle.name,
            nativeHandle.desc
        ));
        handleMethod.instructions.add(new InsnNode(Opcodes.LRETURN));
        classNode.methods.add(handleMethod);
    }

    private static void selectWindowBackend(FieldNode nativeHandle) {
        if (SDL_CONTEXT_FIELD.equals(nativeHandle.name)) {
            WindowBackends.useSdl();
        } else {
            WindowBackends.useGlfw();
        }
    }

    private static FieldNode requireField(ClassNode classNode, String name, String descriptor, boolean isStatic) {
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
        return field;
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
        instructions.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "org/lwjgl/opengl/ContextGL",
            NATIVE_HANDLE_METHOD,
            NATIVE_HANDLE_DESCRIPTOR,
            false
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

    private static InsnList bindContext(String nativeHandleField) {
        final InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            nativeHandleField,
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

    private static InsnList contextDestroyAbort(String nativeHandleField) {
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
            nativeHandleField,
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

    private static InsnList retireContext(String nativeHandleField) {
        final InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            "org/lwjgl/opengl/ContextGL",
            nativeHandleField,
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
        return new IllegalStateException("Cannot transform LWJGL context target " + targetName + ": " + reason);
    }

    @FunctionalInterface
    private interface InstructionFactory {
        InsnList create();
    }
}
