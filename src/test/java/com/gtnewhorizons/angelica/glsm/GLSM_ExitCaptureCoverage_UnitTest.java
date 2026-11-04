package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that the exit-capture recording surface is fully wired: every {@link ExitStateCapture} member that records or
 * rejects state has a production caller, and every facade that reports state reaches the capture.
 *
 * <p>The capture only stays sound while each state write either records the field it changed or rejects the capture.
 * A recording member with no caller, or a facade that silently skips reporting, leaves a capture claiming state it
 * never observed, and a later {@code applyExitState} then restores stale values. The check reads compiled bytecode
 * because loading {@code GLStateManager} needs a live GL context.</p>
 */
class GLSM_ExitCaptureCoverage_UnitTest {

    private static final String PACKAGE = "com/gtnewhorizons/angelica/glsm/";
    private static final String CAPTURE = PACKAGE + "ExitStateCapture";
    private static final String STATE_MANAGER = PACKAGE + "GLStateManager";

    /** Facades whose state writes are modelled by the capture and must report into it. */
    private static final List<String> RECORDING_FACADES = List.of(
        "GLSMBlend", "GLSMCurrentAttribs", "GLSMDepthStencilAlpha", "GLSMEnable", "GLSMLighting", "GLSMMatrices",
        "GLSMRasterizer", "GLSMTextures");

    /** Entry points the shader and Iris layers call; missing one breaks a caller at runtime. */
    private static final List<String> REQUIRED_ENTRY_POINTS = List.of(
        "beginExitCapture(Lcom/gtnewhorizons/angelica/glsm/ExitState;)V",
        "finishExitCapture(Lcom/gtnewhorizons/angelica/glsm/ExitState;)Z",
        "canApplyExitState(Lcom/gtnewhorizons/angelica/glsm/ExitState;)Z",
        "applyExitState(Lcom/gtnewhorizons/angelica/glsm/ExitState;)V",
        "cancelExitCapture()V",
        "suspendExitCapture()Z",
        "resumeExitCapture(Z)V",
        "isExitCaptureActive()Z",
        "requireExitDrawState(Z)V",
        "requireExitVertexDefaults(ZZZZ)V",
        "beginExitTextureFilter(Z)Z",
        "finishExitTextureFilterSave()V",
        "beginExitTextureFilterRestore()Z",
        "finishExitTextureFilterRestore()V",
        "failExitTextureFilter()V",
        "requireExitTextureFilter(IZ)V",
        "getExitPolygonOffsetWrites()I",
        "isExitLightingWritten()Z");

    @Test
    void everyRecordingCaptureMemberHasAProductionCaller() {
        final Set<String> called = new TreeSet<>();
        for (String owner : allGlsmClasses()) {
            if (owner.equals(CAPTURE)) continue;
            for (MethodNode method : load(owner).methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(CAPTURE)) called.add(call.name);
                }
            }
        }
        final List<String> unused = new ArrayList<>();
        for (MethodNode method : load(CAPTURE).methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) == 0) continue;
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (method.name.equals("<init>")) continue;
            if (!called.contains(method.name)) unused.add(method.name + method.desc);
        }
        unused.sort(null);
        assertTrue(unused.isEmpty(), "ExitStateCapture members with no production caller:\n  " + String.join("\n  ", unused));
    }

    @Test
    void everyRecordingFacadeReportsIntoTheCapture() {
        final List<String> silent = new ArrayList<>();
        for (String facade : RECORDING_FACADES) {
            if (!reachesCapture(PACKAGE + facade)) silent.add(facade);
        }
        assertTrue(silent.isEmpty(), "Facades that never reach the exit capture:\n  " + String.join("\n  ", silent));
    }

    @Test
    void glStateManagerExposesEveryEntryPoint() {
        final Set<String> present = new TreeSet<>();
        for (MethodNode method : load(STATE_MANAGER).methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) == 0) continue;
            present.add(method.name + method.desc);
        }
        final List<String> missing = new ArrayList<>();
        for (String entry : REQUIRED_ENTRY_POINTS) {
            if (!present.contains(entry)) missing.add(entry);
        }
        assertTrue(missing.isEmpty(), "Missing GLStateManager exit-capture entry points:\n  " + String.join("\n  ", missing));
    }

    private static boolean reachesCapture(String owner) {
        for (MethodNode method : load(owner).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(CAPTURE)) return true;
            }
        }
        return false;
    }

    private static List<String> allGlsmClasses() {
        return List.of(
            PACKAGE + "GLSMAttribStack", PACKAGE + "GLSMBlend", PACKAGE + "GLSMBuffers",
            PACKAGE + "GLSMCurrentAttribs", PACKAGE + "GLSMDepthStencilAlpha", PACKAGE + "GLSMDraws",
            PACKAGE + "GLSMEnable", PACKAGE + "GLSMFog", PACKAGE + "GLSMFramebuffer", PACKAGE + "GLSMLighting",
            PACKAGE + "GLSMMatrices", PACKAGE + "GLSMPrograms", PACKAGE + "GLSMQueries", PACKAGE + "GLSMRasterizer",
            PACKAGE + "GLSMTexEnvGen", PACKAGE + "GLSMTextures", PACKAGE + "GLSMVertexInput",
            PACKAGE + "GLStateManager", PACKAGE + "RenderSystem");
    }

    private static ClassNode load(String internalName) {
        try (InputStream in = GLSM_ExitCaptureCoverage_UnitTest.class.getClassLoader()
            .getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IllegalStateException("Missing class file for " + internalName);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
