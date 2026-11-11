package com.dhj.actinium.loading.fml.transformers;

import com.dhj.actinium.startup.SdlGpuBootstrap;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import top.outlands.foundation.IExplicitTransformer;

import java.util.List;

/** Routes SDL display and shared drawable operations that remain renderer-specific. */
public final class SdlGpuHostTransformer implements IClassTransformer, IExplicitTransformer {
    private static final String HOOK = "com/dhj/actinium/startup/SdlGpuBootstrap";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final String DRAWABLE = "org/lwjgl/opengl/DrawableGL";
    private static final String SHARED = "org/lwjgl/opengl/SharedDrawable";
    private static final String DRAWABLE_DESCRIPTOR = "Lorg/lwjgl/opengl/DrawableGL;";
    private static final String API_DRAWABLE = "Lorg/lwjgl/opengl/Drawable;";
    public static final List<String> TARGETS = List.of(DISPLAY, DRAWABLE).stream()
        .map(name -> name.replace('/', '.')).toList();

    @Override public int getPriority() { return IClassTransformer.super.getPriority() + 100; }

    @Override public byte[] transform(byte[] bytes) {
        String name = new ClassReader(bytes).getClassName().replace('/', '.');
        return transform(name, name, bytes);
    }

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !SdlGpuBootstrap.isSupported()) return bytes;
        if (!TARGETS.contains(name) && !TARGETS.contains(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (node.fields.stream().anyMatch(field -> field.name.equals("actinium$sdlHost"))) return bytes;
        switch (node.name) {
            case DISPLAY -> transformDisplay(node);
            case DRAWABLE -> transformDrawable(node);
            default -> throw new IllegalStateException("Unexpected SDL host class " + node.name);
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "actinium$sdlHost", "Z", null, 1));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformDisplay(ClassNode node) {
        displayRoute(node, "makeCurrent", "()V", "makeCurrent", "(" + API_DRAWABLE + ")V", true);
        displayRoute(node, "releaseContext", "()V", "releaseContext", "(" + API_DRAWABLE + ")V", true);
        displayRoute(node, "isCurrent", "()Z", "isCurrent", "(" + API_DRAWABLE + ")Z", true);
        displayRoute(node, "swapBuffers", "()V", "present", "()V", false);
        displayRoute(node, "getAdapter", "()Ljava/lang/String;", "adapter", "()Ljava/lang/String;", false);
        displayRoute(node, "getVersion", "()Ljava/lang/String;", "version", "()Ljava/lang/String;", false);
        MethodNode interval = requireMethod(node, "setSwapInterval", "(I)V");
        InsnList route = activeGuard();
        route.add(new VarInsnNode(Opcodes.ILOAD, 0));
        route.add(call("setSwapInterval", "(I)V"));
        endRoute(route, interval);
        interval.instructions.insert(route);
    }

    private static void displayRoute(ClassNode node, String name, String descriptor, String hookName, String hookDescriptor, boolean drawable) {
        MethodNode method = requireMethod(node, name, descriptor);
        InsnList route = activeGuard();
        if (drawable) route.add(new FieldInsnNode(Opcodes.GETSTATIC, DISPLAY, "drawable", DRAWABLE_DESCRIPTOR));
        route.add(call(hookName, hookDescriptor));
        endRoute(route, method);
        method.instructions.insert(route);
    }

    private static void transformDrawable(ClassNode node) {
        for (String name : List.of("makeCurrent", "releaseContext", "isCurrent", "destroy", "checkGLError", "swapBuffers", "setSwapInterval", "initContext")) {
            MethodNode method = node.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Outlands DrawableGL lacks " + name));
            InsnList route = new InsnList();
            LabelNode original = new LabelNode();
            route.add(new VarInsnNode(Opcodes.ALOAD, 0));
            route.add(call("isSharedDrawable", "(Ljava/lang/Object;)Z"));
            route.add(new JumpInsnNode(Opcodes.IFEQ, original));
            switch (name) {
                case "makeCurrent", "releaseContext", "isCurrent" -> {
                    route.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    route.add(call(name, "(" + API_DRAWABLE + ")" + (name.equals("isCurrent") ? "Z" : "V")));
                }
                case "destroy" -> {
                    route.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    route.add(new TypeInsnNode(Opcodes.CHECKCAST, SHARED));
                    route.add(call("destroySharedDrawable", "(L" + SHARED + ";)V"));
                }
                case "swapBuffers" -> route.add(call("present", "()V"));
                case "setSwapInterval" -> {
                    route.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    route.add(call("setSwapInterval", "(I)V"));
                }
                case "checkGLError" -> route.add(call("checkGLError", "()V"));
                case "initContext" -> {
                    route.add(new VarInsnNode(Opcodes.FLOAD, 1));
                    route.add(new VarInsnNode(Opcodes.FLOAD, 2));
                    route.add(new VarInsnNode(Opcodes.FLOAD, 3));
                    route.add(call("initContext", "(FFF)V"));
                }
                default -> throw new IllegalStateException("Unsupported shared drawable operation " + name);
            }
            route.add(new InsnNode(returnOpcode(method.desc)));
            route.add(original);
            method.instructions.insert(route);
        }
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new IllegalStateException(node.name + " lacks " + name + descriptor));
    }

    private static InsnList activeGuard() {
        InsnList route = new InsnList();
        route.add(call("isActive", "()Z"));
        route.add(new JumpInsnNode(Opcodes.IFEQ, new LabelNode()));
        return route;
    }

    private static void endRoute(InsnList route, MethodNode method) {
        JumpInsnNode guard = (JumpInsnNode) route.get(1);
        int returnOpcode = returnOpcode(method.desc);
        if (returnOpcode == Opcodes.RETURN) {
            AbstractInsnNode terminalReturn = method.instructions.getLast();
            while (terminalReturn != null && terminalReturn.getOpcode() != Opcodes.RETURN) terminalReturn = terminalReturn.getPrevious();
            if (terminalReturn == null) throw new IllegalStateException("Outlands method has no return: " + method.name);
            LabelNode exit = new LabelNode();
            method.instructions.insertBefore(terminalReturn, exit);
            route.add(new JumpInsnNode(Opcodes.GOTO, exit));
        } else {
            route.add(new InsnNode(returnOpcode));
        }
        route.add(guard.label);
    }

    private static int returnOpcode(String descriptor) {
        if (descriptor.endsWith("V")) return Opcodes.RETURN;
        return descriptor.endsWith("Z") ? Opcodes.IRETURN : Opcodes.ARETURN;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, name, descriptor, false);
    }
}
