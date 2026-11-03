package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.KHRDebug;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.TEXTURE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.TEXTURE_RENAMED;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.generateVertexShaderIfNeeded;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.initConfig;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isFragmentShader;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isTextureBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.programLifetimeGeneration;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordGpuCommand;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMPrograms {

    private GLSMPrograms() {}

    public static void glUseProgram(int program) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordUseProgram(program);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }

        final ShaderManager ffp = ShaderManager.getInstance();
        if (program == 0 && ffp.isEnabled()) {
            final int prev = ctx().activeProgram;
            final boolean caching = isCachingEnabled();
            if (caching && prev == 0 && ffp.isActive()) {
                return;
            }
            if (caching) {
                if (prev != 0) ctx().programGeneration++;
                ctx().activeProgram = 0; // Track that FFP was requested
            }
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = 0;
                GLSMHooks.programChangeEvent.postBind = false;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
            recordGpuCommand(GpuCommandType.PROGRAM_USE, program, 0);
            ffp.activate();
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = 0;
                GLSMHooks.programChangeEvent.postBind = true;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
            return;
        }

        // Non-zero program or FFP emulation not enabled
        if (ffp.isActive()) {
            ffp.deactivate();
        }

        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || program != ctx().activeProgram) {
            final int prev = ctx().activeProgram;
            if (caching) {
                ctx().programGeneration++;
                ctx().activeProgram = program;
            }
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = program;
                GLSMHooks.programChangeEvent.postBind = false;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
            if (initConfig != null && initConfig.isLwjglDebug()) {
                final String programName = GLDebug.getObjectLabel(KHRDebug.GL_PROGRAM, program);
                GLDebug.debugMessage("Activating Program - " + program + ":" + programName);
            }
            recordGpuCommand(GpuCommandType.PROGRAM_USE, program, 0);
            RENDER_BACKEND.useProgram(program);
            CompatUniformManager.onUseProgram(program);
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = program;
                GLSMHooks.programChangeEvent.postBind = true;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
        }
    }

    public static void glShaderSource(int shader, CharSequence source) {
        String src = source.toString();
        // Always rename reserved words for the target GLSL version (e.g. 'sampler' at 460)
        src = GlslTransformUtils.renameReservedWords(src, RENDER_BACKEND.getMinGLSLVersion());
        if (ShaderManager.getInstance().isEnabled()) {
            src = CompatShaderTransformer.transform(src, isFragmentShader(shader));
        }
        RENDER_BACKEND.shaderSource(shader, src);
    }

    public static void glShaderSource(int shader, ByteBuffer source) {
        final byte[] bytes = new byte[source.remaining()];
        final int pos = source.position();
        source.get(bytes);
        source.position(pos);
        glShaderSource(shader, new String(bytes, StandardCharsets.UTF_8));
    }

    public static void glShaderSource(int shader, CharSequence[] sources) {
        int totalLen = 0;
        for (CharSequence s : sources) totalLen += s.length();
        final StringBuilder sb = new StringBuilder(totalLen);
        for (CharSequence s : sources) sb.append(s);
        glShaderSource(shader, sb.toString());
    }

    public static void glLinkProgram(int program) {
        if (ShaderManager.getInstance().isEnabled()) {
            generateVertexShaderIfNeeded(program);
        }
        long linkEpoch = CompatUniformManager.beforeLinkProgram(program);
        RENDER_BACKEND.linkProgram(program);
        CompatUniformManager.onLinkProgram(program, linkEpoch);
    }

    public static void glDeleteProgram(int program) {
        if (program == 0) return;
        CompatUniformManager.onDeleteProgram(program);

        // GL defers deletion of a bound program until unbind; the program cache entry stays set so the
        // cache matches GL_CURRENT_PROGRAM and the next glUseProgram is not skipped as redundant.
        if (GLSMHooks.PROGRAM_DELETE.hasListeners()) {
            GLSMHooks.programDeleteEvent.program = program;
            GLSMHooks.PROGRAM_DELETE.post(GLSMHooks.programDeleteEvent);
        }
        RENDER_BACKEND.deleteProgram(program);
        programLifetimeGeneration++;
    }

    public static int glCreateShader(int type) {
        return RENDER_BACKEND.createShader(type);
    }

    public static void glCompileShader(int shader) {
        RENDER_BACKEND.compileShader(shader);
    }

    public static int glCreateProgram() {
        final int program = RENDER_BACKEND.createProgram();
        programLifetimeGeneration++;
        return program;
    }

    public static void glAttachShader(int program, int shader) {
        RENDER_BACKEND.attachShader(program, shader);
    }

    public static void glDeleteShader(int shader) {
        RENDER_BACKEND.deleteShader(shader);
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        RENDER_BACKEND.bindAttribLocation(program, index, name);
    }

    public static int glGetShaderi(int shader, int pname) {
        return RENDER_BACKEND.getShaderi(shader, pname);
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return RENDER_BACKEND.getShaderInfoLog(shader, maxLength);
    }

    public static String glGetShaderInfoLog(int shader) {
        return RENDER_BACKEND.getShaderInfoLog(shader);
    }

    public static void glGetShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {
        RENDER_BACKEND.getShaderInfoLog(shader, length, infoLog);
    }

    public static void glGetShaderInfoLog(int shader, int[] length, ByteBuffer infoLog) {
        RENDER_BACKEND.getShaderInfoLog(shader, length, infoLog);
    }

    public static int glGetProgrami(int program, int pname) {
        return RENDER_BACKEND.getProgrami(program, pname);
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return RENDER_BACKEND.getProgramInfoLog(program, maxLength);
    }

    public static String glGetProgramInfoLog(int program) {
        return RENDER_BACKEND.getProgramInfoLog(program);
    }

    public static void glGetProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {
        RENDER_BACKEND.getProgramInfoLog(program, length, infoLog);
    }

    public static void glGetProgramInfoLog(int program, int[] length, ByteBuffer infoLog) {
        RENDER_BACKEND.getProgramInfoLog(program, length, infoLog);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        int loc = RENDER_BACKEND.getUniformLocation(program, name);
        if (loc == -1 && TEXTURE.contentEquals(name)) {
            loc = RENDER_BACKEND.getUniformLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static int glGetUniformLocation(int program, ByteBuffer name) {
        int loc = RENDER_BACKEND.getUniformLocation(program, name);
        if (loc == -1 && isTextureBuffer(name)) {
            loc = RENDER_BACKEND.getUniformLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        int loc = RENDER_BACKEND.getAttribLocation(program, name);
        if (loc == -1 && TEXTURE.contentEquals(name)) {
            loc = RENDER_BACKEND.getAttribLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static int glGetAttribLocation(int program, ByteBuffer name) {
        int loc = RENDER_BACKEND.getAttribLocation(program, name);
        if (loc == -1 && isTextureBuffer(name)) {
            loc = RENDER_BACKEND.getAttribLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static void glUniform1f(int location, float v0) {
        RENDER_BACKEND.uniform1f(location, v0);
    }

    public static void glUniform2f(int location, float v0, float v1) {
        RENDER_BACKEND.uniform2f(location, v0, v1);
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        RENDER_BACKEND.uniform3f(location, v0, v1, v2);
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        RENDER_BACKEND.uniform4f(location, v0, v1, v2, v3);
    }

    public static void glUniform1i(int location, int v0) {
        RENDER_BACKEND.uniform1i(location, v0);
    }

    public static void glUniform2i(int location, int v0, int v1) {
        RENDER_BACKEND.uniform2i(location, v0, v1);
    }

    public static void glUniform3i(int location, int v0, int v1, int v2) {
        RENDER_BACKEND.uniform3i(location, v0, v1, v2);
    }

    public static void glUniformMatrix3(int location, boolean transpose, FloatBuffer matrices) {
        RENDER_BACKEND.uniformMatrix3(location, transpose, matrices);
    }

    public static void glUniformMatrix4(int location, boolean transpose, FloatBuffer matrices) {
        RENDER_BACKEND.uniformMatrix4(location, transpose, matrices);
    }

    public static void glUniformMatrix3(int location, boolean transpose, float[] matrices) {
        RENDER_BACKEND.uniformMatrix3(location, transpose, matrices);
    }

    public static void glUniformMatrix4(int location, boolean transpose, float[] matrices) {
        RENDER_BACKEND.uniformMatrix4(location, transpose, matrices);
    }

    public static void glGetActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        RENDER_BACKEND.getActiveUniform(program, index, length, size, type, name);
    }

    public static void glGetActiveUniform(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) {
        RENDER_BACKEND.getActiveUniform(program, index, length, size, type, name);
    }

    public static String glGetActiveUniform(int program, int index, int maxLength) {
        // Legacy convenience form returning the active uniform name. Third-party mods
        // (e.g. HammerLib) call GL20.glGetActiveUniform(program, index, maxLength) ->
        // String, which the GL redirector rewrites to this class, so the same signature
        // must exist here or the call fails with NoSuchMethodError during mod init.
        IntBuffer sizeType = BufferUtils.createIntBuffer(2);
        return RENDER_BACKEND.getActiveUniform(program, index, maxLength, sizeType);
    }
}
