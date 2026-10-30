package com.dhj.actinium.loading.fml.transformers;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import top.outlands.foundation.IExplicitTransformer;

import java.util.ArrayList;
import java.util.List;

/** Extends the legacy OpenGL 4 context attribute version check to include 4.6. */
public final class LwjglContextVersionTransformer implements IClassTransformer, IExplicitTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final String CONTEXT_ATTRIBS = "org.lwjgl.opengl.ContextAttribs";
    private static final String CONTEXT_ATTRIBS_INTERNAL = "org/lwjgl/opengl/ContextAttribs";
    private static final int GL45 = 5;
    private static final int GL46 = 6;

    @Override
    public int getPriority() {
        return IClassTransformer.super.getPriority();
    }

    @Override
    public byte[] transform(byte[] basicClass) {
        if (basicClass == null) {
            throw transformationFailure("explicit transformation received no class bytes");
        }
        final String className = new ClassReader(basicClass).getClassName().replace('/', '.');
        return transform(className, className, basicClass);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!CONTEXT_ATTRIBS.equals(name) && !CONTEXT_ATTRIBS.equals(transformedName)) {
            return basicClass;
        }
        try {
            if (basicClass == null) {
                throw transformationFailure("no class bytes were supplied");
            }
            final ClassReader reader = new ClassReader(basicClass);
            final ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            if (!CONTEXT_ATTRIBS_INTERNAL.equals(classNode.name)) {
                throw transformationFailure("unexpected class node " + classNode.name);
            }
            if (!extendVersionCheck(classNode)) {
                return basicClass;
            }
            final ClassWriter writer = new ClassWriter(reader, 0);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to extend LWJGL context attribute version validation", failure);
            throw failure;
        }
    }

    private static boolean extendVersionCheck(ClassNode classNode) {
        final List<MethodNode> constructors = classNode.methods.stream()
            .filter(method -> "<init>".equals(method.name) && "(IIII)V".equals(method.desc))
            .toList();
        if (constructors.isEmpty()) {
            return false;
        }
        if (constructors.size() != 1) {
            throw transformationFailure("expected one four-argument constructor, found " + constructors.size());
        }

        final List<MinorVersionCheck> checks = minorVersionChecks(constructors.getFirst());
        if (checks.size() != 1) {
            throw transformationFailure("expected one GL4 minor version check, found " + checks.size());
        }
        final MinorVersionCheck check = checks.getFirst();
        if (check.upperBound() == GL46) {
            return false;
        }
        if (check.upperBound() != GL45) {
            throw transformationFailure("unexpected GL4 minor version upper bound " + check.upperBound());
        }

        constructors.getFirst().instructions.set(
            check.upperBoundInstruction(),
            new IntInsnNode(Opcodes.BIPUSH, GL46)
        );
        return true;
    }

    private static List<MinorVersionCheck> minorVersionChecks(MethodNode constructor) {
        final List<MinorVersionCheck> checks = new ArrayList<>();
        for (AbstractInsnNode instruction = constructor.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!isIntLoad(instruction, 1)
                || nextInstruction(instruction) == null
                || nextInstruction(instruction).getOpcode() != Opcodes.ICONST_4
                || !(nextInstruction(nextInstruction(instruction)) instanceof JumpInsnNode majorBranch)
                || majorBranch.getOpcode() != Opcodes.IF_ICMPNE) {
                continue;
            }

            final AbstractInsnNode firstOperand = nextInstruction(majorBranch);
            final AbstractInsnNode secondOperand = nextInstruction(firstOperand);
            final AbstractInsnNode comparison = nextInstruction(secondOperand);
            if (comparison == null) {
                continue;
            }

            if (!isIntLoad(secondOperand, 2) || comparison.getOpcode() != Opcodes.IF_ICMPLT) {
                continue;
            }
            final AbstractInsnNode boundInstruction = firstOperand;

            final Integer upperBound = integerConstant(boundInstruction);
            if (upperBound != null) {
                checks.add(new MinorVersionCheck(boundInstruction, upperBound));
            }
        }
        return checks;
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode constant && constant.getOpcode() == Opcodes.BIPUSH) {
            return constant.operand;
        }
        if (instruction != null) {
            return switch (instruction.getOpcode()) {
                case Opcodes.ICONST_5 -> GL45;
                default -> null;
            };
        }
        return null;
    }

    private static boolean isIntLoad(AbstractInsnNode instruction, int variable) {
        return instruction instanceof VarInsnNode load
            && load.getOpcode() == Opcodes.ILOAD
            && load.var == variable;
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

    private static IllegalStateException transformationFailure(String reason) {
        return new IllegalStateException("Cannot transform " + CONTEXT_ATTRIBS + ": " + reason);
    }

    private record MinorVersionCheck(AbstractInsnNode upperBoundInstruction, int upperBound) {
    }
}
