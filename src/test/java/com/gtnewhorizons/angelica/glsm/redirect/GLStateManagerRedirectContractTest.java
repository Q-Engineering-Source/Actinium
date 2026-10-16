package com.gtnewhorizons.angelica.glsm.redirect;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every {@code glGetActiveUniform} signature the GL redirector can
 * produce (it rewrites matching {@code org.lwjgl.opengl.GL20} calls by method name,
 * preserving the caller's descriptor) actually exists on the GLSM
 * {@code GLStateManager}.
 *
 * <p>HammerLib calls {@code GL20.glGetActiveUniform(int, int, int)} returning
 * {@code String} (issue #40); the redirector rewrites that to
 * {@code GLStateManager.glGetActiveUniform(III)Ljava/lang/String;}, which was
 * missing and failed on mod init with {@code NoSuchMethodError}. A unit test
 * cannot load {@code GLStateManager} because its static initializer requires a
 * live GL context, so the contract is asserted against the compiled class bytes
 * instead (same pattern as {@code MixinConfigurationTest}).</p>
 */
class GLStateManagerRedirectContractTest {
    private static final String GL_STATE_MANAGER = "com/gtnewhorizons.angelica.glsm.GLStateManager";
    private static final String GL_STATE_MANAGER_FILE = GL_STATE_MANAGER.replace('.', '/') + ".class";

    private static final String LEGACY_STRING_FORM = "(III)Ljava/lang/String;";
    private static final String BUFFER_FORM =
        "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/ByteBuffer;)V";

    @Test
    void glGetActiveUniformLegacyStringFormExists() throws IOException {
        assertTrue(
            glGetActiveUniformDescriptors().contains(LEGACY_STRING_FORM),
            "GLStateManager must expose glGetActiveUniform(int, int, int) returning String: "
                + "the GL redirector rewrites HammerLib's GL20.glGetActiveUniform(III)Ljava/lang/String; "
                + "call to it (issue #40)"
        );
    }

    @Test
    void glGetActiveUniformBufferFormStillExists() throws IOException {
        assertTrue(
            glGetActiveUniformDescriptors().contains(BUFFER_FORM),
            "GLStateManager must keep the buffer-based glGetActiveUniform overload"
        );
    }

    @Test
    void glRotatefFloatFormExists() throws IOException {
        assertTrue(
            methodDescriptors("glRotatef").contains("(FFFF)V"),
            "GLStateManager must expose glRotatef(float, float, float, float), the redirect target "
                + "for vanilla GlStateManager.rotate(float, float, float, float)"
        );
    }

    @Test
    void glRotatefDoubleAngleFormExists() throws IOException {
        assertTrue(
            methodDescriptors("glRotatef").contains("(DFFF)V"),
            "GLStateManager must expose glRotatef(double, float, float, float): the GL redirector "
                + "rewrites NTM-CE's GlStateManager.rotate(double, float, float, float) call to it "
                + "while preserving the (DFFF)V descriptor (issue #64)"
        );
    }

    @Test
    void outlineModeMethodsExistForVanillaGlowRendering() throws IOException {
        assertTrue(
            methodDescriptors("enableOutlineMode").contains("(I)V"),
            "GLStateManager must expose enableOutlineMode(int): the GL redirector rewrites vanilla "
                + "GlStateManager.func_187431_e(I)V to it, and RenderLivingBase calls it whenever a living entity "
                + "is glowing (spectral arrow, glowing potion, etc.) — missing it crashes rendering with "
                + "NoSuchMethodError"
        );
        assertTrue(
            methodDescriptors("disableOutlineMode").contains("()V"),
            "GLStateManager must expose disableOutlineMode() to match the redirector registration"
        );
    }

    @Test
    void colorMatrixGetterExists() throws IOException {
        assertTrue(
            methodDescriptors("getColorMatrix").contains("()Lorg/joml/Matrix4fStack;"),
            "GLStateManager must expose getColorMatrix() for GL_COLOR matrix operations"
        );
    }

    @Test
    void colorMatrixModeIsRoutedToTrackedColorStack() throws IOException {
        assertTrue(
            methodReferencesField("getMatrixStack", "colorMatrix"),
            "GLStateManager.getMatrixStack() must route GL11.GL_COLOR mode to the tracked colorMatrix "
                + "stack (issue #134): without it Minecraft.resetGlStates() dies with "
                + "IllegalStateException: Unknown matrix mode: 6144"
        );
        assertTrue(
            methodReferencesField("bumpMatrixGeneration", "colorMatrixGeneration"),
            "GLStateManager.bumpMatrixGeneration() must bump colorMatrixGeneration for GL_COLOR mode "
                + "so color-matrix edits invalidate cached state like the other matrix stacks"
        );
    }

    @Test
    void arbUniformBufferFormsExist() throws IOException {
        for (String name : new String[] {"glUniform1ARB", "glUniform2ARB", "glUniform3ARB", "glUniform4ARB"}) {
            Set<String> descriptors = methodDescriptors(name);
            assertTrue(
                descriptors.contains("(ILjava/nio/FloatBuffer;)V"),
                "GLStateManager must expose " + name + "(int, FloatBuffer): the GL redirector rewrites "
                    + "ARBShaderObjects." + name + " calls to it while preserving the descriptor, and a missing "
                    + "overload crashes the caller with NoSuchMethodError (Dynamic Surroundings aurora shader, "
                    + "issue #137)"
            );
            assertTrue(
                descriptors.contains("(ILjava/nio/IntBuffer;)V"),
                "GLStateManager must expose " + name + "(int, IntBuffer) to cover every descriptor the "
                    + "redirector can produce for ARBShaderObjects." + name
            );
        }
    }

    /**
     * Every signature the redirector can produce for the kirino/OpenGlHelper/IrisGlDebug batch
     * (SDL-GPU port) must exist on GLStateManager, or the rewritten call dies with
     * NoSuchMethodError at runtime.
     */
    @Test
    void sdlGpuPortBatchEntryPointsExist() throws IOException {
        String[][] sigs = {
            {"glCreateTextures", "(I)I"},
            {"glBindTextureUnit", "(II)V"},
            {"glTextureParameteri", "(III)V"},
            {"glTextureParameterf", "(IIF)V"},
            {"glTextureParameteriv", "(II[I)V"},
            {"glTextureParameterfv", "(II[F)V"},
            {"glTextureParameterIiv", "(II[I)V"},
            {"glTextureParameterIuiv", "(II[I)V"},
            {"glTexParameteriv", "(II[I)V"},
            {"glTexParameterfv", "(II[F)V"},
            {"glTexParameterIiv", "(II[I)V"},
            {"glTexParameterIuiv", "(II[I)V"},
            {"glTexStorage3D", "(IIIIII)V"},
            {"glTextureStorage3D", "(IIIIII)V"},
            {"glTextureSubImage3D", "(IIIIIIIIIILjava/nio/ByteBuffer;)V"},
            {"glCompressedTexImage3D", "(IIIIIIILjava/nio/ByteBuffer;)V"},
            {"glCompressedTexSubImage3D", "(IIIIIIIIILjava/nio/ByteBuffer;)V"},
            {"glCompressedTextureSubImage3D", "(IIIIIIIIILjava/nio/ByteBuffer;)V"},
            {"glCopyTextureSubImage3D", "(IIIIIIIII)V"},
            {"glClearTexSubImage", "(IIIIIIIIIILjava/nio/ByteBuffer;)V"},
            {"glGenerateTextureMipmap", "(I)V"},
            {"glGetTextureLevelParameteri", "(III)I"},
            {"glGetTextureLevelParameterf", "(III)F"},
            {"glGetTexLevelParameterf", "(III)F"},
            {"glGetTextureImage", "(IIIILjava/nio/ByteBuffer;)V"},
            {"glGetCompressedTextureImage", "(IILjava/nio/ByteBuffer;)V"},
            {"glGetCompressedTexImage", "(IILjava/nio/ByteBuffer;)V"},
            {"glMultiDrawArraysIndirect", "(IJII)V"},
            {"glUniform1ui", "(II)V"},
            {"glGetUniformf", "(II)F"},
            {"glGetUniformi", "(II)I"},
            {"glGetUniform", "(IILjava/nio/FloatBuffer;)V"},
            {"glUniform1", "(ILjava/nio/IntBuffer;)V"},
            {"glUniform2", "(ILjava/nio/IntBuffer;)V"},
            {"glUniform2", "(ILjava/nio/FloatBuffer;)V"},
            {"glUniform3", "(ILjava/nio/IntBuffer;)V"},
            {"glUniform4", "(ILjava/nio/IntBuffer;)V"},
            {"glUniformMatrix2", "(IZLjava/nio/FloatBuffer;)V"},
            {"glGetBoolean", "(ILjava/nio/ByteBuffer;)V"},
        };
        for (String[] sig : sigs) {
            assertTrue(
                methodDescriptors(sig[0]).contains(sig[1]),
                "GLStateManager must expose " + sig[0] + sig[1] + ": the GL redirector rewrites calls to it"
            );
        }
    }

    private static Set<String> methodDescriptors(String methodName) throws IOException {
        return loadClassNode().methods.stream()
            .filter(method -> method.name.equals(methodName))
            .map(method -> method.desc)
            .collect(Collectors.toSet());
    }

    private static Set<String> glGetActiveUniformDescriptors() throws IOException {
        return methodDescriptors("glGetActiveUniform");
    }

    private static boolean methodReferencesField(String methodName, String fieldName) throws IOException {
        for (MethodNode method : loadClassNode().methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode fieldAccess && fieldAccess.name.equals(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClassNode loadClassNode() throws IOException {
        ClassNode classNode = new ClassNode();
        try (InputStream in = GLStateManagerRedirectContractTest.class.getClassLoader()
            .getResourceAsStream(GL_STATE_MANAGER_FILE)) {
            if (in == null) {
                throw new IOException("Could not find " + GL_STATE_MANAGER_FILE + " on the test classpath");
            }
            new ClassReader(in).accept(classNode, 0);
        }
        return classNode;
    }
}