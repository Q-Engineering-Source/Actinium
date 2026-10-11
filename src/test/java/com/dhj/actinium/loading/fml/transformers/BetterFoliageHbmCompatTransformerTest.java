package com.dhj.actinium.loading.fml.transformers;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetterFoliageHbmCompatTransformerTest {
    private static final String HOOK_OWNER = "mods/betterfoliage/client/Hooks";
    private static final String HOOK_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/BlockRendererDispatcher;"
                    + "Lnet/minecraft/block/state/IBlockState;"
                    + "Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "Lnet/minecraft/util/BlockRenderLayer;)Z";
    private static final MethodInsnNode VANILLA_RENDER_BLOCK = new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/BlockRendererDispatcher",
            "renderBlock",
            "(Lnet/minecraft/block/state/IBlockState;"
                    + "Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;)Z",
            false
    );

    @Test
    void restoresDispatcherCallAndDropsTheExtraLayerArgument() {
        ClassNode target = new ClassNode();
        MethodNode rebuildChunk = new MethodNode(Opcodes.ACC_PUBLIC, "rebuildChunk", "()V", null, null);
        for (int argument = 0; argument < 6; argument++) {
            rebuildChunk.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        }
        rebuildChunk.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HOOK_OWNER,
                "renderWorldBlock",
                HOOK_DESCRIPTOR,
                false
        ));
        rebuildChunk.instructions.add(new InsnNode(Opcodes.POP));
        rebuildChunk.instructions.add(new InsnNode(Opcodes.RETURN));
        target.methods.add(rebuildChunk);

        int restored = BetterFoliageHbmCompatTransformer.restoreBlockRenderCalls(
                target,
                HOOK_DESCRIPTOR,
                VANILLA_RENDER_BLOCK
        );

        MethodInsnNode actualCall = Arrays.stream(rebuildChunk.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(1, restored);
        assertEquals(Opcodes.POP, actualCall.getPrevious().getOpcode());
        assertEquals(VANILLA_RENDER_BLOCK.getOpcode(), actualCall.getOpcode());
        assertEquals(VANILLA_RENDER_BLOCK.owner, actualCall.owner);
        assertEquals(VANILLA_RENDER_BLOCK.name, actualCall.name);
        assertEquals(VANILLA_RENDER_BLOCK.desc, actualCall.desc);
        assertEquals(VANILLA_RENDER_BLOCK.itf, actualCall.itf);
        assertEquals(0, BetterFoliageHbmCompatTransformer.restoreBlockRenderCalls(
                target,
                HOOK_DESCRIPTOR,
                VANILLA_RENDER_BLOCK
        ));
    }
}
