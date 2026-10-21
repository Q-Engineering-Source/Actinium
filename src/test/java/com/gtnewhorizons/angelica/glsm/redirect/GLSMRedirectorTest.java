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
    void redirectsCoreAndArbVertexBindingDivisorsToGlsm() {
        for (String owner : new String[] {
            "org/lwjgl/opengl/GL43C",
            "org/lwjgl/opengl/ARBVertexAttribBinding"
        }) {
            final ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/VertexBindingDivisor", null, "java/lang/Object", null);
            final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
            method.visitCode();
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "glVertexBindingDivisor", "(II)V", false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(2, 1);
            method.visitEnd();
            writer.visitEnd();
            final byte[] classBytes = writer.toByteArray();
            final GLSMRedirector redirector = new GLSMRedirector();

            assertTrue(redirector.shouldTransform(classBytes), owner + " vertex binding divisor call must be a redirect candidate");
            final ClassNode classNode = new ClassNode();
            new ClassReader(classBytes).accept(classNode, 0);
            assertTrue(redirector.transformClassNode("sample/VertexBindingDivisor", classNode));

            final MethodNode render = classNode.methods.stream()
                .filter(candidate -> candidate.name.equals("render"))
                .findFirst()
                .orElseThrow();
            boolean redirected = false;
            for (AbstractInsnNode instruction = render.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(GLSM_GL_STATE_MANAGER)
                    && call.name.equals("glVertexBindingDivisor")
                    && call.desc.equals("(II)V")) {
                    redirected = true;
                    break;
                }
            }
            assertTrue(redirected, owner + " call must be redirected to GLSM GLStateManager");
        }
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
     * LWJGL helper classes (Util/GLUtil) are core-excluded from transformation, so their GL-issuing
     * calls are intercepted at the call sites inside mod classes (xaero, Distant Horizons).
     */
    @Test
    void rewritesLwjglHelperCallSitesToGlsm() {
        String[][] cases = {
            {"org/lwjgl/opengl/Util", "checkGLError", "()V", "checkGLError"},
            {"org/lwjgl/opengl/GLUtil", "setupDebugMessageCallback", "(Ljava/io/PrintStream;)Lorg/lwjgl/system/Callback;", "setupDebugMessageCallback"},
            {"org/lwjgl/opengl/GL13", "glCompressedTexImage2D", "(IIIIIIIJ)V", "glCompressedTexImage2D"},
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
     * GLContext.getCapabilities() starts with the org/lwjgl/opengl/GL prefix, so only the desc-map
     * entry can catch it; the call (com.hbm.render.GLCompat clinit, vanilla OpenGlHelper) must be
     * rewritten to the GLSM facade.
     */
    @Test
    void rewritesGlContextCapabilitiesProbeToGlsm() {
        assertRedirectedToGlsm("org/lwjgl/opengl/GLContext", "getCapabilities",
            "()Lorg/lwjgl/opengl/ContextCapabilities;", "currentContextCapabilities");
    }

    /**
     * FluxLoading's loading shader queries GL directly during world load; all six calls must land
     * on the GLSM entry points instead of the raw GL.
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

    /**
     * The kirino engine batch: every DSA/3D-texture entry point the font atlas and GUI renderer
     * reach must land on the GLSM GLStateManager instead of the raw GL.
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
     * tracked uniform entry points.
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
}
