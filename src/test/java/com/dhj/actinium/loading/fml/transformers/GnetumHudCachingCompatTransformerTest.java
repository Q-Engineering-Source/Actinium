package com.dhj.actinium.loading.fml.transformers;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GnetumHudCachingCompatTransformer} mirrors every
 * constant {@code Gnetum.rendering} store onto {@code GLStateManager.setHudCacheOverride},
 * using the real {@code GuiIngameForgeMixin} class bytes from the Gnetum
 * dependency.
 */
class GnetumHudCachingCompatTransformerTest {
    private static final String MIXIN_RESOURCE =
        "me/decce/gnetum/mixins/early/GuiIngameForgeMixin.class";
    private static final String MIXIN_CLASS =
        "me.decce.gnetum.mixins.early.GuiIngameForgeMixin";
    private static final String GNETUM_OWNER = "me/decce/gnetum/Gnetum";
    private static final String GL_STATE_MANAGER_OWNER =
        "com/gtnewhorizons/angelica/glsm/GLStateManager";

    @Test
    void transformMirrorsEveryRenderingStore() throws IOException {
        byte[] original = readMixinClass();
        byte[] transformed = new GnetumHudCachingCompatTransformer()
            .transform(MIXIN_CLASS, MIXIN_CLASS, original);

        assertNotNull(transformed);
        assertNotSame(original, transformed, "target class must be rewritten");
        assertTrue(transformed.length > original.length,
            "mirrored calls must add instructions");

        List<String> events = collectMirrorEvents(transformed);
        assertEquals(List.of(
            "override=true",
            "mirror=true",
            "override=false",
            "mirror=false"
        ), events);
    }

    @Test
    void transformLeavesUnrelatedClassesUntouched() throws IOException {
        byte[] original = readMixinClass();
        GnetumHudCachingCompatTransformer transformer = new GnetumHudCachingCompatTransformer();

        assertArrayEquals(original, transformer.transform("a.b.C", "a.b.C", original));
        assertArrayEquals(original, transformer.transform(null, "some.other.Class", original));
        assertNull(transformer.transform(MIXIN_CLASS, MIXIN_CLASS, null));
    }

    private static byte[] readMixinClass() throws IOException {
        try (InputStream stream = GnetumHudCachingCompatTransformerTest.class
            .getClassLoader().getResourceAsStream(MIXIN_RESOURCE)) {
            assertNotNull(stream,
                "Gnetum must be on the test classpath to provide " + MIXIN_RESOURCE);
            return stream.readAllBytes();
        }
    }

    private static List<String> collectMirrorEvents(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);

        List<String> events = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {

                if (instruction.getOpcode() != Opcodes.PUTSTATIC) {
                    continue;
                }
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (!GNETUM_OWNER.equals(field.owner)) {
                    continue;
                }

                if ("rendering".equals(field.name) && "Z".equals(field.desc)) {
                    int value = constantBefore(instruction);
                    assertTrue(value >= 0, "rendering store must be preceded by a constant push");
                    events.add("override=" + (value == 1));

                    AbstractInsnNode mirrorPush = instruction.getNext();
                    assertNotNull(mirrorPush, "rendering store must be followed by a mirrored store");
                    assertEquals(value == 1 ? Opcodes.ICONST_1 : Opcodes.ICONST_0,
                        mirrorPush.getOpcode(),
                        "mirrored store must re-push the mirrored constant");
                    AbstractInsnNode mirrorStore = mirrorPush.getNext();
                    assertNotNull(mirrorStore);
                    assertEquals(Opcodes.INVOKESTATIC, mirrorStore.getOpcode());
                    MethodInsnNode mirrorCall = (MethodInsnNode) mirrorStore;
                    assertEquals(GL_STATE_MANAGER_OWNER, mirrorCall.owner);
                    assertEquals("setHudCacheOverride", mirrorCall.name);
                    assertEquals("(Z)V", mirrorCall.desc);
                    events.add("mirror=" + (value == 1));
                } else if ("renderingCanceled".equals(field.name)) {
                    AbstractInsnNode next = instruction.getNext();
                    if (next != null && next.getOpcode() == Opcodes.PUTSTATIC) {
                        assertNotEquals(GL_STATE_MANAGER_OWNER, ((FieldInsnNode) next).owner,
                            "renderingCanceled stores must not be mirrored");
                    }
                }
            }
        }
        return events;
    }

    private static int constantBefore(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        if (previous == null) {
            return -1;
        }
        if (previous.getOpcode() == Opcodes.ICONST_1) {
            return 1;
        }
        if (previous.getOpcode() == Opcodes.ICONST_0) {
            return 0;
        }
        return -1;
    }
}
