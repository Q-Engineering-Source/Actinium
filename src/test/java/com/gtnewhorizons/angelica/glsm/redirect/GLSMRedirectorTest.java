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

    /**
     * The kirino engine batch (issue: SDL-GPU port): every DSA/3D-texture entry point the font
     * atlas and GUI renderer reach must land on the GLSM GLStateManager instead of the raw GL.
     */
    @Test
    void rewritesKirinoTextureAndDrawCallsToGlsm() {
        String[][] cases = {
            {"org/lwjgl/opengl/GL45", "glCreateTextures", "(I)I", "glCreateTextures"},
            {"org/lwjgl/opengl/GL45", "glTextureParameteri", "(III)V", "glTextureParameteri"},
            {"org/lwjgl/opengl/GL45", "glTextureParameterf", "(IIF)V", "glTextureParameterf"},
            {"org/lwjgl/opengl/GL45", "glTextureStorage3D", "(IIIIII)V", "glTextureStorage3D"},
            {"org/lwjgl/opengl/GL45", "glTextureSubImage3D", "(IIIIIIIIIILjava/nio/ByteBuffer;)V", "glTextureSubImage3D"},
            {"org/lwjgl/opengl/GL45", "glCompressedTextureSubImage3D", "(IIIIIIIIILjava/nio/ByteBuffer;)V", "glCompressedTextureSubImage3D"},
            {"org/lwjgl/opengl/GL45", "glCopyTextureSubImage3D", "(IIIIIIIII)V", "glCopyTextureSubImage3D"},
            {"org/lwjgl/opengl/GL45", "glGenerateTextureMipmap", "(I)V", "glGenerateTextureMipmap"},
            {"org/lwjgl/opengl/GL45", "glGetTextureLevelParameteri", "(III)I", "glGetTextureLevelParameteri"},
            {"org/lwjgl/opengl/GL45", "glGetTextureLevelParameterf", "(III)F", "glGetTextureLevelParameterf"},
            {"org/lwjgl/opengl/GL45C", "glBindTextureUnit", "(II)V", "glBindTextureUnit"},
            {"org/lwjgl/opengl/GL45C", "glClearTexSubImage", "(IIIIIIIIIILjava/nio/ByteBuffer;)V", "glClearTexSubImage"},
            {"org/lwjgl/opengl/GL45C", "glGetTextureImage", "(IIIILjava/nio/ByteBuffer;)V", "glGetTextureImage"},
            {"org/lwjgl/opengl/GL45C", "glGetCompressedTextureImage", "(IILjava/nio/ByteBuffer;)V", "glGetCompressedTextureImage"},
            {"org/lwjgl/opengl/GL45C", "glTextureParameteriv", "(II[I)V", "glTextureParameteriv"},
            {"org/lwjgl/opengl/GL45C", "glTextureParameterfv", "(II[F)V", "glTextureParameterfv"},
            {"org/lwjgl/opengl/GL45C", "glTextureParameterIiv", "(II[I)V", "glTextureParameterIiv"},
            {"org/lwjgl/opengl/GL45C", "glTextureParameterIuiv", "(II[I)V", "glTextureParameterIuiv"},
            {"org/lwjgl/opengl/GL42", "glTexStorage3D", "(IIIIII)V", "glTexStorage3D"},
            {"org/lwjgl/opengl/GL13", "glCompressedTexImage3D", "(IIIIIIILjava/nio/ByteBuffer;)V", "glCompressedTexImage3D"},
            {"org/lwjgl/opengl/GL13", "glCompressedTexSubImage3D", "(IIIIIIIIILjava/nio/ByteBuffer;)V", "glCompressedTexSubImage3D"},
            {"org/lwjgl/opengl/GL13", "glGetCompressedTexImage", "(IILjava/nio/ByteBuffer;)V", "glGetCompressedTexImage"},
            {"org/lwjgl/opengl/GL11C", "glTexParameterfv", "(II[F)V", "glTexParameterfv"},
            {"org/lwjgl/opengl/GL11C", "glTexParameteriv", "(II[I)V", "glTexParameteriv"},
            {"org/lwjgl/opengl/GL30C", "glTexParameterIiv", "(II[I)V", "glTexParameterIiv"},
            {"org/lwjgl/opengl/GL30C", "glTexParameterIuiv", "(II[I)V", "glTexParameterIuiv"},
            {"org/lwjgl/opengl/GL11", "glGetTexLevelParameterf", "(III)F", "glGetTexLevelParameterf"},
            {"org/lwjgl/opengl/GL43", "glCopyImageSubData", "(IIIIIIIIIIIIIII)V", "glCopyImageSubData"},
            {"org/lwjgl/opengl/GL43", "glMultiDrawArraysIndirect", "(IJII)V", "glMultiDrawArraysIndirect"},
            {"org/lwjgl/opengl/GL30", "glUniform1ui", "(II)V", "glUniform1ui"},
        };
        for (String[] c : cases) {
            assertRedirectedToGlsm(c[0], c[1], c[2], c[3]);
        }
    }

    /**
     * OpenGlHelper's uniform upload paths (LWJGL2 GL20 + ARBShaderObjects owners) must reach the
     * tracked uniform entry points; missing ones crash the SDL backend through a fake GL pointer.
     */
    @Test
    void rewritesOpenGlHelperUniformCallsToGlsm() {
        String[][] cases = {
            {"org/lwjgl/opengl/GL20", "glUniform1iv", "(ILjava/nio/IntBuffer;)V", "glUniform1"},
            {"org/lwjgl/opengl/GL20", "glUniform2iv", "(ILjava/nio/IntBuffer;)V", "glUniform2"},
            {"org/lwjgl/opengl/GL20", "glUniform3iv", "(ILjava/nio/IntBuffer;)V", "glUniform3"},
            {"org/lwjgl/opengl/GL20", "glUniform4iv", "(ILjava/nio/IntBuffer;)V", "glUniform4"},
            {"org/lwjgl/opengl/GL20", "glUniform2fv", "(ILjava/nio/FloatBuffer;)V", "glUniform2"},
            {"org/lwjgl/opengl/GL20", "glUniformMatrix2fv", "(IZLjava/nio/FloatBuffer;)V", "glUniformMatrix2"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform1fvARB", "(ILjava/nio/FloatBuffer;)V", "glUniform1ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform2fvARB", "(ILjava/nio/FloatBuffer;)V", "glUniform2ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform3fvARB", "(ILjava/nio/FloatBuffer;)V", "glUniform3ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform4fvARB", "(ILjava/nio/FloatBuffer;)V", "glUniform4ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform1ivARB", "(ILjava/nio/IntBuffer;)V", "glUniform1ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform2ivARB", "(ILjava/nio/IntBuffer;)V", "glUniform2ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform3ivARB", "(ILjava/nio/IntBuffer;)V", "glUniform3ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniform4ivARB", "(ILjava/nio/IntBuffer;)V", "glUniform4ARB"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniformMatrix2fvARB", "(IZLjava/nio/FloatBuffer;)V", "glUniformMatrix2"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniformMatrix3fvARB", "(IZLjava/nio/FloatBuffer;)V", "glUniformMatrix3"},
            {"org/lwjgl/opengl/ARBShaderObjects", "glUniformMatrix4fvARB", "(IZLjava/nio/FloatBuffer;)V", "glUniformMatrix4"},
        };
        for (String[] c : cases) {
            assertRedirectedToGlsm(c[0], c[1], c[2], c[3]);
        }
    }

    /** IrisGlDebug's state queries must be served by GLSM (cached state) rather than the driver. */
    @Test
    void rewritesIrisGlDebugQueryCallsToGlsm() {
        String[][] cases = {
            {"org/lwjgl/opengl/GL11", "glGetBooleanv", "(ILjava/nio/ByteBuffer;)V", "glGetBoolean"},
            {"org/lwjgl/opengl/GL20", "glGetUniformf", "(II)F", "glGetUniformf"},
            {"org/lwjgl/opengl/GL20", "glGetUniformi", "(II)I", "glGetUniformi"},
            {"org/lwjgl/opengl/GL20", "glGetUniformfv", "(IILjava/nio/FloatBuffer;)V", "glGetUniform"},
        };
        for (String[] c : cases) {
            assertRedirectedToGlsm(c[0], c[1], c[2], c[3]);
        }
    }

    /**
     * NTM-CE's GLCompat ARB entry points, vanilla GlStateManager's FloatBuffer FFP setters, and
     * xaero's compressed 2D upload must all land on the tracked GLStateManager paths.
     */
    @Test
    void rewritesHbmCompatAndVanillaFfpCallsToGlsm() {
        String[][] cases = {
            {"org/lwjgl/opengl/ARBFramebufferObject", "glBlitFramebuffer", "(IIIIIIIIII)V", "glBlitFramebuffer"},
            {"org/lwjgl/opengl/ARBImaging", "glBlendEquation", "(I)V", "glBlendEquation"},
            {"org/lwjgl/opengl/ARBOcclusionQuery", "glDeleteQueriesARB", "(I)V", "glDeleteQueries"},
            {"org/lwjgl/opengl/ARBOcclusionQuery", "glGetQueryObjectuiARB", "(II)I", "glGetQueryObjectui"},
            {"org/lwjgl/opengl/ARBVertexProgram", "glDisableVertexAttribArrayARB", "(I)V", "glDisableVertexAttribArray"},
            {"org/lwjgl/opengl/ARBVertexProgram", "glEnableVertexAttribArrayARB", "(I)V", "glEnableVertexAttribArray"},
            {"org/lwjgl/opengl/ARBVertexProgram", "glVertexAttribPointerARB", "(IIIZIJ)V", "glVertexAttribPointer"},
            {"org/lwjgl/opengl/ARBVertexShader", "glBindAttribLocationARB", "(IILjava/lang/CharSequence;)V", "glBindAttribLocation"},
            {"org/lwjgl/opengl/EXTFramebufferBlit", "glBlitFramebufferEXT", "(IIIIIIIIII)V", "glBlitFramebuffer"},
            {"org/lwjgl/opengl/GL11", "glFogfv", "(ILjava/nio/FloatBuffer;)V", "glFog"},
            {"org/lwjgl/opengl/GL11", "glLightModelfv", "(ILjava/nio/FloatBuffer;)V", "glLightModel"},
            {"org/lwjgl/opengl/GL11", "glLightfv", "(IILjava/nio/FloatBuffer;)V", "glLight"},
            {"org/lwjgl/opengl/GL11", "glTexEnvfv", "(IILjava/nio/FloatBuffer;)V", "glTexEnv"},
            {"org/lwjgl/opengl/GL11", "glTexGenfv", "(IILjava/nio/FloatBuffer;)V", "glTexGen"},
            {"org/lwjgl/opengl/GL13", "glCompressedTexImage2D", "(IIIIIILjava/nio/ByteBuffer;)V", "glCompressedTexImage2D"},
        };
        for (String[] c : cases) {
            assertRedirectedToGlsm(c[0], c[1], c[2], c[3]);
        }
    }

    /**
     * GLContext.getCapabilities() probes the driver natively under LWJGLY, which crashes backends
     * that own no real GL context (com.hbm.render.GLCompat clinit). The call must be rewritten to
     * the GLSM facade that prefers the backend's capabilities override.
     */
    @Test
    void rewritesGlContextCapabilitiesProbeToGlsm() {
        assertRedirectedToGlsm("org/lwjgl/opengl/GLContext", "getCapabilities",
            "()Lorg/lwjgl/opengl/ContextCapabilities;", "currentContextCapabilities");
    }

    /**
     * FluxLoading's loading shader queries GL directly during world load; all six calls must land
     * on the GLSM entry points instead of the sentinel GL pointers.
     */
    @Test
    void rewritesFluxLoadingShaderCallsToGlsm() {
        String[][] cases = {
            {"org/lwjgl/opengl/GL15", "glGetQueryObject", "(IILjava/nio/IntBuffer;)V", "glGetQueryObject"},
            {"org/lwjgl/opengl/GL20", "glGetVertexAttrib", "(IILjava/nio/IntBuffer;)V", "glGetVertexAttrib"},
            {"org/lwjgl/opengl/GL30", "glUniform2ui", "(III)V", "glUniform2ui"},
            {"org/lwjgl/opengl/GL30", "glUniform3ui", "(IIII)V", "glUniform3ui"},
            {"org/lwjgl/opengl/GL30", "glUniform4ui", "(IIIII)V", "glUniform4ui"},
            {"org/lwjgl/opengl/GL40", "glUniform1d", "(ID)V", "glUniform1d"},
        };
        for (String[] c : cases) {
            assertRedirectedToGlsm(c[0], c[1], c[2], c[3]);
        }
    }

    private static void assertRedirectedToGlsm(String owner, String name, String desc, String expectedName) {
        final String label = owner + "." + name + desc;
        byte[] classBytes = generateClassCallingGl(owner, name, desc);
        GLSMRedirector redirector = new GLSMRedirector();
        assertTrue(redirector.shouldTransform(classBytes), "class referencing " + label + " must be a redirect candidate");

        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        assertTrue(redirector.transformClassNode("sample/GlCaller", cn), label + " must be rewritten");

        MethodNode render = cn.methods.stream()
            .filter(m -> m.name.equals("render"))
            .findFirst()
            .orElseThrow();
        boolean redirected = false;
        boolean originalSurvived = false;
        for (AbstractInsnNode node : render.instructions) {
            if (node instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                if (call.owner.equals(GLSM_GL_STATE_MANAGER) && call.name.equals(expectedName) && call.desc.equals(desc)) {
                    redirected = true;
                } else if (call.owner.equals(owner) && call.name.equals(name)) {
                    originalSurvived = true;
                }
            }
        }
        assertTrue(redirected, label + " must be redirected to GLSM GLStateManager." + expectedName + desc);
        assertEquals(false, originalSurvived, "no driver-side " + label + " call may survive the rewrite");
    }

    private static byte[] generateClassCallingGl(String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/GlCaller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        final String ret = desc.substring(desc.indexOf(')') + 1);
        switch (ret) {
            case "V" -> { }
            case "J", "D" -> mv.visitInsn(Opcodes.POP2);
            default -> mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(16, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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
