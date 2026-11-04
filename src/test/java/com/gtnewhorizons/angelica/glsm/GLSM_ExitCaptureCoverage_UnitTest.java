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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that every public static {@code gl*} entry point on a GLSM facade either rejects an open exit capture or
 * records the state it writes, directly or through another GLSM call.
 *
 * <p>The capture only stays sound while each state write either records the field it changed or rejects the capture.
 * An entry point that does neither leaves a capture claiming state it never observed, and a later
 * {@code applyExitState} then restores stale values. The check reads compiled bytecode because loading
 * {@code GLStateManager} needs a live GL context.</p>
 */
class GLSM_ExitCaptureCoverage_UnitTest {

    private static final String PACKAGE = "com/gtnewhorizons/angelica/glsm/";
    private static final String CAPTURE = PACKAGE + "ExitStateCapture";

    private static final List<String> FACADES = List.of(
        "GLSMAttribStack", "GLSMBlend", "GLSMBuffers", "GLSMCurrentAttribs", "GLSMDepthStencilAlpha", "GLSMDraws",
        "GLSMEnable", "GLSMFog", "GLSMFramebuffer", "GLSMLighting", "GLSMMatrices", "GLSMPrograms", "GLSMQueries",
        "GLSMRasterizer", "GLSMTexEnvGen", "GLSMTextures", "GLSMVertexInput");

    /**
     * Entry points that touch no render state the capture models, or that only report GL object bookkeeping.
     * {@code glVertex*} are excluded because {@code glBegin} and {@code glEnd} already reject the capture.
     */
    private static final Set<String> IGNORED = Set.of(
        "glVertex2d", "glVertex2f", "glVertex2i", "glVertex3d", "glVertex3f", "glVertex3i",
        "glVertexAttrib2f", "glVertexAttrib2s", "glVertexAttrib3f", "glVertexAttrib4f",
        "glDebugMessageCallback", "glDebugMessageControl", "glDebugMessageInsert", "glObjectLabel",
        "glPushDebugGroup", "glPopDebugGroup", "glInitNames", "glPushName", "glPopName", "glLoadName",
        "gluUnProject", "gluErrorString",
        // Object naming and allocation only; nothing here changes render state.
        "glGenBuffers", "glCreateBuffers", "glGenFramebuffers", "glGenRenderbuffers", "glGenQueries",
        "glGenSamplers", "glCreateTextures", "glCreateProgram", "glCreateShader",
        // Queries that read back GL objects the capture does not model.
        "glGetActiveAttrib", "glGetActiveUniform", "glGetAttachedShaders", "glGetProgramInfoLog",
        "glGetShaderInfoLog", "glGetShaderSource",
        // Uniform uploads write shader state the capture does not restore.
        "glUniform1", "glUniform2", "glUniform3", "glUniform4", "glUniformMatrix2", "glUniformMatrix3",
        "glUniformMatrix4",
        // Sampler objects and draw buffers are independent of the modelled state.
        "glBindSampler", "glBindSamplers", "glDrawBuffers",
        // Unsupported fixed-function entry points that only warn or throw.
        "glAccum", "glPixelTransferf", "glPixelTransferi", "glRasterPos2d", "glRasterPos2f", "glRasterPos2i",
        "glRasterPos3d", "glRasterPos3f", "glRasterPos3i", "glRasterPos4d", "glRasterPos4f", "glRasterPos4i",
        "glPolygonStipple", "glSampleCoverage",
        // Named framebuffer attachment helpers delegate into guarded entry points.
        "glNamedFramebufferRenderbuffer", "glNamedFramebufferTextureLayer");

    private final Map<String, ClassNode> nodes = new HashMap<>();
    private final Set<String> reaching = new HashSet<>();
    private final Set<String> notReaching = new HashSet<>();

    @Test
    void everyEntryPointRejectsOrRecordsTheExitCapture() {
        final List<String> missing = new ArrayList<>();
        for (String facade : FACADES) {
            final ClassNode node = load(PACKAGE + facade);
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) continue;
                if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                if (!method.name.startsWith("gl") || IGNORED.contains(method.name)) continue;
                if (!reachesCapture(PACKAGE + facade, method)) missing.add(facade + '.' + method.name + method.desc);
            }
        }
        missing.sort(null);
        assertTrue(missing.isEmpty(), "Entry points that neither reject nor record an exit capture:\n  " + String.join("\n  ", missing));
    }

    private boolean reachesCapture(String owner, MethodNode root) {
        return search(owner, root, new HashSet<>());
    }

    private boolean search(String owner, MethodNode method, Set<String> visited) {
        final String key = owner + '.' + method.name + method.desc;
        if (reaching.contains(key)) return true;
        if (notReaching.contains(key) || !visited.add(key)) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(CAPTURE)) {
                reaching.add(key);
                return true;
            }
            if (!call.owner.startsWith(PACKAGE)) continue;
            final MethodNode target = resolve(call.owner, call.name, call.desc);
            if (target != null && search(call.owner, target, visited)) {
                reaching.add(key);
                return true;
            }
        }
        notReaching.add(key);
        return false;
    }

    private MethodNode resolve(String owner, String name, String desc) {
        final ClassNode node = load(owner);
        if (node == null) return null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && method.instructions.size() > 0) return method;
        }
        return null;
    }

    private ClassNode load(String internalName) {
        return nodes.computeIfAbsent(internalName, name -> {
            try (InputStream in = GLSM_ExitCaptureCoverage_UnitTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
                if (in == null) throw new IllegalStateException("Missing class file for " + name);
                final ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return node;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
