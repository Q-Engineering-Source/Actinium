package com.gtnewhorizons.angelica.glsm.shader;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * The production {@link ProgramBinaryOps}: every call lands on the GLSM entry points, so the state
 * tracking, the uniform epoch protocol and the debug naming wrap saved-program handling exactly like they
 * wrap a source link.
 *
 * <p>Keeping the GL calls here instead of in {@link ProgramBinaryCache} also keeps the loader free of
 * static GL access, which is what makes the cache testable without a context.</p>
 */
final class ProgramBinaryOpsImpl implements ProgramBinaryOps {

    @Override
    public boolean isSupported() {
        return RenderSystem.supportsProgramBinary();
    }

    @Override
    public String driverFingerprint() {
        return RENDER_BACKEND.getString(GL11.GL_VENDOR) + '|' + RENDER_BACKEND.getString(GL11.GL_RENDERER) + '|'
            + RENDER_BACKEND.getString(GL11.GL_VERSION) + '|'
            + RENDER_BACKEND.getString(GL20.GL_SHADING_LANGUAGE_VERSION) + '|' + RenderSystem.isGLES() + '|'
            + RENDER_BACKEND.getMinGLSLVersion();
    }

    @Override
    public int createProgram() {
        return GLStateManager.glCreateProgram();
    }

    @Override
    public void deleteProgram(int program) {
        GLStateManager.glDeleteProgram(program);
    }

    @Override
    public int getProgrami(int program, int pname) {
        return GLStateManager.glGetProgrami(program, pname);
    }

    @Override
    public void programParameteri(int program, int pname, int value) {
        GLStateManager.glProgramParameteri(program, pname, value);
    }

    @Override
    public void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer binary) {
        GLStateManager.glGetProgramBinary(program, length, binaryFormat, binary);
    }

    @Override
    public void programBinary(int program, int binaryFormat, ByteBuffer binary) {
        GLStateManager.glProgramBinary(program, binaryFormat, binary);
    }
}
