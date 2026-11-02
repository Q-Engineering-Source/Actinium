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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link StellarCoreHudCachingCompatTransformer} mirrors
 * StellarCore's {@code renderingCacheOverride} window onto
 * {@code GLStateManager.setHudCacheOverride} and restores the HUD baseline right
 * before the cached {@code renderGameOverlay} call, using the real
 * {@code HUDCaching} class bytes from the StellarCore dependency.
 */
class StellarCoreHudCachingCompatTransformerTest {
    private static final String HUD_CACHING_RESOURCE =
        "github/kasuminova/stellarcore/client/hudcaching/HUDCaching.class";
    private static final String HUD_CACHING_CLASS =
        "github.kasuminova.stellarcore.client.hudcaching.HUDCaching";
    private static final String OVERRIDE_OWNER =
        "github/kasuminova/stellarcore/client/hudcaching/HUDCaching";
    private static final String GLSM_STATE_MANAGER_OWNER =
        "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private static final String BOUNDARY_OWNER =
        "com/dhj/actinium/render/GuiGlStateBoundary";

    @Test
    void transformMirrorsOverrideWindowAndRestoresBaseline() throws IOException {
        byte[] original = readHudCachingClass();
        byte[] transformed = new StellarCoreHudCachingCompatTransformer()
            .transform(HUD_CACHING_CLASS, HUD_CACHING_CLASS, original);

        assertNotNull(transformed);
        assertNotSame(original, transformed, "target class must be rewritten");
        assertTrue(transformed.length > original.length,
            "mirrored calls and baseline restore must add instructions");

        List<String> events = collectRenderCachedHudEvents(transformed);
        assertEquals(List.of(
            "renderGameOverlay",                    // non-cached early path, no restore
            "override=true",
            "hudOverride=true",
            "restoreHudBaseline",
            "renderGameOverlay",                    // cached render, restore applied
            "override=false",
            "hudOverride=false"
        ), events);
    }

    @Test
    void transformLeavesUnrelatedClassesUntouched() throws IOException {
        byte[] original = readHudCachingClass();
        StellarCoreHudCachingCompatTransformer transformer = new StellarCoreHudCachingCompatTransformer();

        assertArrayEquals(original, transformer.transform("a.b.C", "a.b.C", original));
        assertArrayEquals(original, transformer.transform(null, "some.other.Class", original));
        assertNull(transformer.transform(HUD_CACHING_CLASS, HUD_CACHING_CLASS, null));
    }

    private static byte[] readHudCachingClass() throws IOException {
        try (InputStream stream = StellarCoreHudCachingCompatTransformerTest.class
            .getClassLoader().getResourceAsStream(HUD_CACHING_RESOURCE)) {
            assertNotNull(stream,
                "StellarCore must be on the test classpath to provide " + HUD_CACHING_RESOURCE);
            return stream.readAllBytes();
        }
    }

    private static List<String> collectRenderCachedHudEvents(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);

        MethodNode renderCachedHud = node.methods.stream()
            .filter(method -> "renderCachedHud".equals(method.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("renderCachedHud not found"));

        List<String> events = new ArrayList<>();
        for (AbstractInsnNode instruction = renderCachedHud.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {

            if (instruction.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (OVERRIDE_OWNER.equals(field.owner) && "renderingCacheOverride".equals(field.name)) {
                    int value = constantBefore(instruction);
                    events.add("override=" + (value == 1));
                    assertEquals(value == 1 ? Opcodes.ICONST_1 : Opcodes.ICONST_0,
                        instruction.getPrevious().getOpcode(),
                        "original override store must still be preceded by its constant");
                }
            } else if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                && instruction instanceof MethodInsnNode call
                && GLSM_STATE_MANAGER_OWNER.equals(call.owner)
                && "setHudCacheOverride".equals(call.name)) {
                int value = constantBefore(instruction);
                events.add("hudOverride=" + (value == 1));
                assertEquals("(Z)V", call.desc);
                assertEquals(value == 1 ? Opcodes.ICONST_1 : Opcodes.ICONST_0,
                    instruction.getPrevious().getOpcode(),
                    "setter call must re-push the override constant");
            } else if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                && instruction instanceof MethodInsnNode call
                && BOUNDARY_OWNER.equals(call.owner)
                && "restoreHudBaseline".equals(call.name)) {
                events.add("restoreHudBaseline");
            } else if (instruction.getOpcode() == Opcodes.INVOKEVIRTUAL
                && instruction instanceof MethodInsnNode call
                && "net/minecraft/client/gui/GuiIngame".equals(call.owner)
                && "renderGameOverlay".equals(call.name)) {
                events.add("renderGameOverlay");
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
