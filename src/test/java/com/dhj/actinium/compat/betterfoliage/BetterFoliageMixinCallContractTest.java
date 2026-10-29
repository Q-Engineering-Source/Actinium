package com.dhj.actinium.compat.betterfoliage;

import com.dhj.actinium.mixin.mod.betterfoliage.MixinChunkBuilderMeshingTaskBetterFoliage;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetterFoliageMixinCallContractTest {
    @Test
    void fallbackHandlerCallsTheCompatBaseLayerPredicate() throws IOException {
        String resourceName = Type.getInternalName(MixinChunkBuilderMeshingTaskBetterFoliage.class) + ".class";
        ClassNode mixinClass = new ClassNode();
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(resourceName),
                "Missing Better Foliage mixin class")) {
            new ClassReader(stream).accept(mixinClass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        MethodNode handler = mixinClass.methods.stream()
                .filter(method -> method.name.equals("actinium$betterFoliageRenderVanillaBlock"))
                .findFirst()
                .orElse(null);
        assertNotNull(handler, "Missing Better Foliage dispatcher wrapper");

        String predicateDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(IBlockState.class),
                Type.getType(BlockRenderLayer.class)
        );
        boolean callsLayerPredicate = false;
        for (var instruction = handler.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(Type.getInternalName(BetterFoliageCompat.class))
                    && invocation.name.equals("shouldRenderBaseBlockInLayer")
                    && invocation.desc.equals(predicateDescriptor)) {
                callsLayerPredicate = true;
                break;
            }
        }

        assertTrue(callsLayerPredicate,
                "The conditional Better Foliage mixin must use the compat base-layer policy");
    }
}
