package com.gtnewhorizons.angelica.glsm.redirect;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the GLSM redirector rewrites vanilla
 * {@code net.minecraft.client.renderer.GlStateManager} calls (e.g. the color set
 * used by JourneyMap's map grid) to the GLSM state cache. This is the mechanism
 * that keeps the FFP shader's {@code u_CurrentColor} uniform in sync with vanilla
 * fixed-function color calls on the core-profile context.
 */
class GLSMRedirectorTest {

    private static final String VANILLA_GL_STATE_MANAGER = "net/minecraft/client/renderer/GlStateManager";
    private static final String GLSM_GL_STATE_MANAGER = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private static final String EXT_TIMER_QUERY = "org/lwjgl/opengl/EXTTimerQuery";

    @Test
    void rewritesVanillaGlStateManagerColorToGlsmCache() {
        byte[] classBytes = generateClassCallingVanillaColor();
        GLSMRedirector redirector = new GLSMRedirector();

        assertTrue(redirector.shouldTransform(classBytes), "class referencing GlStateManager.color must be a redirect candidate");

        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        boolean changed = redirector.transformClassNode("sample/Class", cn);
        assertTrue(changed, "GlStateManager.color call must be rewritten");

        MethodNode render = cn.methods.stream()
            .filter(m -> m.name.equals("render"))
            .findFirst()
            .orElseThrow();
        boolean redirected = false;
        for (AbstractInsnNode node : render.instructions) {
            if (node instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(GLSM_GL_STATE_MANAGER)
                && call.name.equals("glColor4f")) {
                redirected = true;
                break;
            }
        }
        assertTrue(redirected, "call must be redirected to GLSM GLStateManager.glColor4f");
    }

    @Test
    void rewritesVanillaGlStateManagerOutlineModeToGlsmCache() {
        byte[] classBytes = generateClassCallingVanillaOutlineMode();
        GLSMRedirector redirector = new GLSMRedirector();

        assertTrue(redirector.shouldTransform(classBytes), "class referencing GlStateManager.enableOutlineMode must be a redirect candidate");

        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        boolean changed = redirector.transformClassNode("sample/Class", cn);
        assertTrue(changed, "GlStateManager.enableOutlineMode call must be rewritten");

        MethodNode render = cn.methods.stream()
            .filter(m -> m.name.equals("render"))
            .findFirst()
            .orElseThrow();
        boolean redirected = false;
        for (AbstractInsnNode node : render.instructions) {
            if (node instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(GLSM_GL_STATE_MANAGER)
                && call.name.equals("enableOutlineMode")
                && call.desc.equals("(I)V")) {
                redirected = true;
                break;
            }
        }
        assertTrue(redirected, "call must be redirected to GLSM GLStateManager.enableOutlineMode(I)V");
    }

    @Test
    void rewritesVanillaRotateDoubleToGlsmGlRotatefPreservingDescriptor() {
        // NTM-CE calls GlStateManager.rotate(double, float, float, float) from its tile entity
        // item renderers (issue #64); the redirector must rewrite it to the GLSM GLStateManager
        // with the same (DFFF)V descriptor so the runtime link matches an existing overload.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Rotator", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(90.0D); // double angle
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, VANILLA_GL_STATE_MANAGER, "rotate", "(DFFF)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        cw.visitEnd();

        byte[] classBytes = cw.toByteArray();
        GLSMRedirector redirector = new GLSMRedirector();

        assertTrue(redirector.shouldTransform(classBytes), "class referencing GlStateManager.rotate must be a redirect candidate");

        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        boolean changed = redirector.transformClassNode("sample/Rotator", cn);
        assertTrue(changed, "GlStateManager.rotate call must be rewritten");

        MethodNode render = cn.methods.stream()
            .filter(m -> m.name.equals("render"))
            .findFirst()
            .orElseThrow();
        boolean redirected = false;
        for (AbstractInsnNode node : render.instructions) {
            if (node instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(GLSM_GL_STATE_MANAGER)
                && call.name.equals("glRotatef")
                && call.desc.equals("(DFFF)V")) {
                redirected = true;
                break;
            }
        }
        assertTrue(redirected, "GlStateManager.rotate(DFFF) must be redirected to GLSM GLStateManager.glRotatef(DFFF)");
    }

    @Test
    void rewritesDebugMessageCallbackToGlsmEntryPoint() {
        // Mods registering a KHR_debug callback through any of the three LWJGL owner classes must
        // land on GLStateManager.glDebugMessageCallback so the active render backend sees them.
        for (String glOwner : new String[]{"org/lwjgl/opengl/KHRDebug", "org/lwjgl/opengl/GL43", "org/lwjgl/opengl/GL43C"}) {
            byte[] classBytes = generateClassCallingDebugMessageCallback(glOwner);
            GLSMRedirector redirector = new GLSMRedirector();

            assertTrue(redirector.shouldTransform(classBytes), "class referencing " + glOwner + " must be a redirect candidate");

            ClassNode cn = new ClassNode();
            new ClassReader(classBytes).accept(cn, 0);
            boolean changed = redirector.transformClassNode("sample/DebugCallback", cn);
            assertTrue(changed, glOwner + ".glDebugMessageCallback call must be rewritten");

            MethodNode render = cn.methods.stream()
                .filter(m -> m.name.equals("render"))
                .findFirst()
                .orElseThrow();
            boolean redirected = false;
            for (AbstractInsnNode node : render.instructions) {
                if (node instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(GLSM_GL_STATE_MANAGER)
                    && call.name.equals("glDebugMessageCallback")
                    && call.desc.equals("(Lorg/lwjgl/opengl/KHRDebugCallback;)V")) {
                    redirected = true;
                    break;
                }
            }
            assertTrue(redirected, glOwner + ".glDebugMessageCallback must be redirected to GLSM GLStateManager with the KHRDebugCallback descriptor");
        }
    }

    /**
     * The EXT form of the 64-bit timer query is what 1.12.2 mods reach for when they time GPU work
     * through the extension entry point; it has to land on the same GLSM entry point as the ARB form.
     */
    @Test
    void rewritesExtTimerQueryToTheGlsmQueryEntryPoint() {
        byte[] classBytes = generateClassCallingExtTimerQuery();
        GLSMRedirector redirector = new GLSMRedirector();

        assertTrue(redirector.shouldTransform(classBytes), "class referencing EXTTimerQuery must be a redirect candidate");

        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        assertTrue(redirector.transformClassNode("sample/TimerQuery", cn), "EXTTimerQuery.glGetQueryObjectuEXT must be rewritten");

        MethodNode render = cn.methods.stream()
            .filter(m -> m.name.equals("render"))
            .findFirst()
            .orElseThrow();
        boolean redirected = false;
        boolean driverCallSurvived = false;
        for (AbstractInsnNode node : render.instructions) {
            if (node instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                if (call.owner.equals(GLSM_GL_STATE_MANAGER) && call.name.equals("glGetQueryObjectui64")) {
                    redirected = true;
                } else if (call.owner.equals(EXT_TIMER_QUERY)) {
                    driverCallSurvived = true;
                }
            }
        }
        assertTrue(redirected, "EXTTimerQuery.glGetQueryObjectuEXT must be redirected to GLSM GLStateManager.glGetQueryObjectui64");
        assertEquals(false, driverCallSurvived, "no driver-side EXTTimerQuery call may survive the rewrite");
    }

    /**
     * Distant Horizons probes the current GL context through GLFW's static entry point, which does
     * not exist on LWJGLY-era runtimes; the call has to land on the window-system facade instead.
     */
    @Test
    void rewritesGlfwCurrentContextProbeToTheWindowSystemFacade() {
        byte[] classBytes = generateClassCallingGlfwCurrentContext();
        GLSMRedirector redirector = new GLSMRedirector();

        assertTrue(redirector.shouldTransform(classBytes), "class referencing GLFW.glfwGetCurrentContext must be a redirect candidate");

        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        assertTrue(redirector.transformClassNode("sample/GLProxy", cn), "GLFW.glfwGetCurrentContext must be rewritten");

        MethodNode probe = cn.methods.stream()
            .filter(m -> m.name.equals("probe"))
            .findFirst()
            .orElseThrow();
        boolean redirected = false;
        boolean glfwCallSurvived = false;
        for (AbstractInsnNode node : probe.instructions) {
            if (node instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                if (call.owner.equals(GLSM_GL_STATE_MANAGER) && call.name.equals("currentContext") && call.desc.equals("()J")) {
                    redirected = true;
                } else if (call.owner.equals("org/lwjgl/glfw/GLFW")) {
                    glfwCallSurvived = true;
                }
            }
        }
        assertTrue(redirected, "GLFW.glfwGetCurrentContext must be redirected to GLSM GLStateManager.currentContext");
        assertEquals(false, glfwCallSurvived, "no GLFW call may survive the rewrite");
    }

    @Test
    void untouchedClassWithoutGlReferencesIsNotTransformed() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Plain", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "ping", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();

        GLSMRedirector redirector = new GLSMRedirector();
        assertEquals(false, redirector.shouldTransform(cw.toByteArray()));
    }

    private static byte[] generateClassCallingDebugMessageCallback(String glOwner) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/DebugCallback", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, glOwner, "glDebugMessageCallback", "(Lorg/lwjgl/opengl/KHRDebugCallback;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCallingVanillaColor() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Class", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(0.5F);
        mv.visitLdcInsn(0.5F);
        mv.visitLdcInsn(0.5F);
        mv.visitLdcInsn(0.5F);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, VANILLA_GL_STATE_MANAGER, "color", "(FFFF)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCallingVanillaOutlineMode() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Class", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(0xFFFF00);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, VANILLA_GL_STATE_MANAGER, "enableOutlineMode", "(I)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, VANILLA_GL_STATE_MANAGER, "disableOutlineMode", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCallingExtTimerQuery() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/TimerQuery", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EXT_TIMER_QUERY, "glGetQueryObjectuEXT", "(II)J", false);
        mv.visitInsn(Opcodes.POP2);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCallingGlfwCurrentContext() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/GLProxy", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwGetCurrentContext", "()J", false);
        mv.visitInsn(Opcodes.POP2);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
