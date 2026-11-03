package com.gtnewhorizons.angelica.client.rendering;

import com.gtnewhorizon.gtnhlib.client.opengl.UniversalVAO;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchej123.glsm.GLStateManagerService;
import net.coderbot.iris.debug.IrisGlDebug;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.IntBuffer;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

public class AngelicaGLStateManagerService implements GLStateManagerService {
    private IntBuffer intArrayBuffer = BufferUtils.createIntBuffer(16);

    public AngelicaGLStateManagerService() {
        IrisGlDebug.logDebugInfo("glsm-service instantiated class={}", getClass().getName());
    }

    private IntBuffer getIntArrayBuffer(int size) {
        if (intArrayBuffer.capacity() < size) {
            intArrayBuffer = BufferUtils.createIntBuffer(size);
        }
        intArrayBuffer.clear();
        return intArrayBuffer;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public int glGetInteger(int pname) {
        return GLSMQueries.glGetInteger(pname);
    }

    @Override
    public String glGetString(int pname) {
        return GLSMQueries.glGetString(pname);
    }

    @Override
    public int glGenFramebuffers() {
        return GLSMFramebuffer.glGenFramebuffers();
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        GLSMFramebuffer.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        GLSMFramebuffer.glBindFramebuffer(target, framebuffer);
    }

    @Override
    public int glCheckFramebufferStatus(int target) {
        return GLSMFramebuffer.glCheckFramebufferStatus(target);
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GLSMFramebuffer.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    @Override
    public int glCreateShader(int type) {
        return GLSMPrograms.glCreateShader(type);
    }

    @Override
    public void glCompileShader(int shader) {
        GLSMPrograms.glCompileShader(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GLSMPrograms.glGetShaderi(shader, pname);
    }

    @Override
    public String glGetShaderInfoLog(int shader, int maxLength) {
        return GLSMPrograms.glGetShaderInfoLog(shader, maxLength);
    }

    @Override
    public void glDeleteShader(int shader) {
        GLSMPrograms.glDeleteShader(shader);
    }

    @Override
    public int glCreateProgram() {
        return GLSMPrograms.glCreateProgram();
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GLSMPrograms.glAttachShader(program, shader);
    }

    @Override
    public void glLinkProgram(int program) {
        GLSMPrograms.glLinkProgram(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GLSMPrograms.glGetProgrami(program, pname);
    }

    @Override
    public void glUseProgram(int program) {
        GLSMPrograms.glUseProgram(program);
    }

    @Override
    public void glDeleteProgram(int program) {
        GLSMPrograms.glDeleteProgram(program);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GLSMPrograms.glGetUniformLocation(program, name);
    }

    @Override
    public void glUniform1i(int location, int value) {
        GLSMPrograms.glUniform1i(location, value);
    }

    @Override
    public int glGetAttribLocation(int program, CharSequence name) {
        return GLSMPrograms.glGetAttribLocation(program, name);
    }

    @Override
    public void glBindAttribLocation(int program, int index, CharSequence name) {
        GLSMPrograms.glBindAttribLocation(program, index, name);
    }

    @Override
    public int glGenVertexArrays() {
        return UniversalVAO.genVertexArrays();
    }

    @Override
    public void glBindVertexArray(int array) {
        GLSMVertexInput.glBindVertexArray(array);
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        GLSMVertexInput.glDeleteVertexArrays(array);
    }

    @Override
    public int glGenBuffers() {
        return GLSMBuffers.glGenBuffers();
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GLSMBuffers.glBindBuffer(target, buffer);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        GLSMBuffers.glDeleteBuffers(buffer);
    }

    @Override
    public int glGenTextures() {
        return GLSMTextures.glGenTextures();
    }

    @Override
    public void glGenTextures(int[] textures) {
        IntBuffer buffer = getIntArrayBuffer(textures.length);
        GLSMTextures.glGenTextures(buffer);
        buffer.get(textures);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GLSMTextures.glDeleteTextures(texture);
    }

    @Override
    public void glDeleteTextures(int[] textures) {
        IntBuffer buffer = getIntArrayBuffer(textures.length);
        buffer.put(textures);
        buffer.flip();
        GLSMTextures.glDeleteTextures(buffer);
    }

    @Override
    public void glActiveTexture(int texture) {
        GLSMTextures.glActiveTexture(texture);
    }

    @Override
    public int glGetTexLevelParameteri(int target, int level, int pname) {
        return GLSMTextures.glGetTexLevelParameteri(target, level, pname);
    }

    @Override
    public int glGetTexLevelParameter(int target, int level, int pname) {
        return GLSMTextures.glGetTexLevelParameteri(target, level, pname);
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        GLSMTextures.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GLSMTextures.glPixelStorei(pname, param);
    }

    @Override
    public void enableCullFace() {
        GLStateManager.enableCull();
    }

    @Override
    public void disableCullFace() {
        GLStateManager.disableCull();
    }

    @Override
    public void enableBlend() {
        GLStateManager.enableBlend();
    }

    @Override
    public void disableBlend() {
        GLStateManager.disableBlend();
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        GLStateManager.tryBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    @Override
    public void enableDepthTest() {
        GLStateManager.enableDepthTest();
    }

    @Override
    public void disableDepthTest() {
        GLStateManager.disableDepthTest();
    }

    @Override
    public void glDepthFunc(int func) {
        GLSMDepthStencilAlpha.glDepthFunc(func);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GLSMDepthStencilAlpha.glDepthMask(flag);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GLSMRasterizer.glViewport(x, y, width, height);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GLSMBlend.glColorMask(red, green, blue, alpha);
    }

    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        GLSMBlend.glClearColor(red, green, blue, alpha);
    }

    @Override
    public void glClear(int mask) {
        GLSMFramebuffer.glClear(mask);
    }

    @Override
    public void clear(int mask, boolean checkError) {
        GLSMFramebuffer.glClear(mask);
    }

    @Override
    public void bindTexture(int texture) {
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    @Override
    public int getActiveTexture() {
        return GLStateManager.getActiveTextureUnit() + GL13.GL_TEXTURE0;
    }

    @Override
    public int getActiveTextureAccessor() {
        return GLStateManager.getActiveTextureUnit();
    }

    @Override
    public int getBoundTexture(int internalUnit) {
        return GLStateManager.getBoundTextureForServerState(internalUnit);
    }

    @Override
    public int getActiveBoundTexture() {
        return GLStateManager.getBoundTextureForServerState();
    }

    @Override
    public int getViewportWidth() {
        return GLStateManager.getViewportState().width;
    }

    @Override
    public int getViewportHeight() {
        return GLStateManager.getViewportState().height;
    }

    @Override
    public boolean getDepthStateMask() {
        return GLStateManager.getDepthState().isMaskEnabled();
    }

    @Override
    public boolean isBlendEnabled() {
        return GLStateManager.getBlendMode().isEnabled();
    }

    @Override
    public void setBoundTexture(int unit, int texture) {
        int currentUnit = GLStateManager.getActiveTextureUnit();
        if (currentUnit != unit) {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        }
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        if (currentUnit != unit) {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + currentUnit);
        }
    }

    @Override
    public int getTextureBinding(int unit) {
        return GLStateManager.getBoundTextureForServerState(unit);
    }
}
