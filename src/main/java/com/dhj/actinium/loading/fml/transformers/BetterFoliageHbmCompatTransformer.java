package com.dhj.actinium.loading.fml.transformers;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Restores HBM's vanilla render call after Better Foliage replaces it with its RenderChunk hook.
 */
public final class BetterFoliageHbmCompatTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final String RENDER_CHUNK = "net.minecraft.client.renderer.chunk.RenderChunk";
    private static final String BETTER_FOLIAGE_HOOK_RESOURCE = "mods/betterfoliage/client/Hooks.class";
    private static final String HBM_RENDER_CHUNK_MIXIN_RESOURCE = "com/hbm/mixin/MixinRenderChunk.class";
    private static final String BETTER_FOLIAGE_HOOK_OWNER = "mods/betterfoliage/client/Hooks";
    private static final String BETTER_FOLIAGE_HOOK_METHOD = "renderWorldBlock";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !isRenderChunk(name, transformedName) || !hasBothMods()) {
            return basicClass;
        }

        ClassReader classReader = new ClassReader(basicClass);
        ClassNode classNode = new ClassNode();
        classReader.accept(classNode, ClassReader.SKIP_DEBUG);

        BetterFoliageHook hook = readBetterFoliageHook();
        int restoredCalls = restoreBlockRenderCalls(classNode, hook.descriptor, hook.vanillaCall);
        if (restoredCalls == 0) {
            if (containsVanillaCall(classNode, hook.vanillaCall)) {
                return basicClass;
            }

            throw new IllegalStateException(
                    "Better Foliage and HBM are installed, but RenderChunk contains neither the "
                            + "Better Foliage hook nor the BlockRendererDispatcher call HBM requires"
            );
        }

        LOGGER.info(
                "Restored {} vanilla RenderChunk block-render call(s) for Better Foliage / HBM compatibility",
                restoredCalls
        );
        ClassWriter classWriter = new ClassWriter(classReader, 0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private static boolean isRenderChunk(String name, String transformedName) {
        return RENDER_CHUNK.equals(name) || RENDER_CHUNK.equals(transformedName);
    }

    private static boolean hasBothMods() {
        ClassLoader classLoader = Launch.classLoader;
        return classLoader.getResource(BETTER_FOLIAGE_HOOK_RESOURCE) != null
                && classLoader.getResource(HBM_RENDER_CHUNK_MIXIN_RESOURCE) != null;
    }

    /**
     * Reads the dispatcher invocation from Better Foliage itself so names and descriptors match
     * both the development and remapped runtime classpaths.
     */
    private static BetterFoliageHook readBetterFoliageHook() {
        try (InputStream stream = Launch.classLoader.getResourceAsStream(BETTER_FOLIAGE_HOOK_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Better Foliage hook class: " + BETTER_FOLIAGE_HOOK_RESOURCE);
            }

            ClassNode hookClass = new ClassNode();
            new ClassReader(stream).accept(hookClass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : hookClass.methods) {
                if (!BETTER_FOLIAGE_HOOK_METHOD.equals(method.name)) {
                    continue;
                }

                Type[] hookArguments = Type.getArgumentTypes(method.desc);
                if (hookArguments.length != 6 || !Type.BOOLEAN_TYPE.equals(Type.getReturnType(method.desc))) {
                    continue;
                }

                String dispatcherOwner = hookArguments[0].getInternalName();
                Type[] dispatcherArguments = {
                        hookArguments[1],
                        hookArguments[2],
                        hookArguments[3],
                        hookArguments[4]
                };
                String dispatcherDescriptor = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, dispatcherArguments);
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null;
                     instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode methodCall)
                            || methodCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !dispatcherOwner.equals(methodCall.owner)
                            || !dispatcherDescriptor.equals(methodCall.desc)) {
                        continue;
                    }

                    return new BetterFoliageHook(
                            method.desc,
                            new MethodInsnNode(
                                    methodCall.getOpcode(),
                                    methodCall.owner,
                                    methodCall.name,
                                    methodCall.desc,
                                    methodCall.itf
                            )
                    );
                }

                throw new IllegalStateException(
                        "Better Foliage Hooks.renderWorldBlock no longer calls BlockRendererDispatcher.renderBlock"
                );
            }

            throw new IllegalStateException(
                    "Better Foliage hook class has no compatible Hooks.renderWorldBlock method"
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Better Foliage hook bytecode", exception);
        }
    }

    /**
     * Replaces each static Better Foliage hook invocation with the vanilla call HBM wraps.
     */
    static int restoreBlockRenderCalls(
            ClassNode classNode,
            String hookDescriptor,
            MethodInsnNode vanillaCall
    ) {
        int restored = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof MethodInsnNode methodCall
                        && methodCall.getOpcode() == Opcodes.INVOKESTATIC
                        && BETTER_FOLIAGE_HOOK_OWNER.equals(methodCall.owner)
                        && BETTER_FOLIAGE_HOOK_METHOD.equals(methodCall.name)
                        && hookDescriptor.equals(methodCall.desc)) {
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(Opcodes.POP));
                    replacement.add(new MethodInsnNode(
                            vanillaCall.getOpcode(),
                            vanillaCall.owner,
                            vanillaCall.name,
                            vanillaCall.desc,
                            vanillaCall.itf
                    ));
                    method.instructions.insertBefore(instruction, replacement);
                    method.instructions.remove(instruction);
                    restored++;
                }
                instruction = next;
            }
        }
        return restored;
    }

    private static boolean containsVanillaCall(ClassNode classNode, MethodInsnNode vanillaCall) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode methodCall
                        && vanillaCall.getOpcode() == methodCall.getOpcode()
                        && vanillaCall.owner.equals(methodCall.owner)
                        && vanillaCall.name.equals(methodCall.name)
                        && vanillaCall.desc.equals(methodCall.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class BetterFoliageHook {
        private final String descriptor;
        private final MethodInsnNode vanillaCall;

        private BetterFoliageHook(String descriptor, MethodInsnNode vanillaCall) {
            this.descriptor = descriptor;
            this.vanillaCall = vanillaCall;
        }
    }
}
