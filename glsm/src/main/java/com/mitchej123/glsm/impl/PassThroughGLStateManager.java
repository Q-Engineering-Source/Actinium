package com.mitchej123.glsm.impl;

import com.mitchej123.glsm.GLStateManagerService;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

public class PassThroughGLStateManager implements GLStateManagerService {
    private IntBuffer intArrayBuffer = BufferUtils.createIntBuffer(16);

    private IntBuffer getIntArrayBuffer(int size) {
        if (intArrayBuffer.capacity() < size) {
            intArrayBuffer = BufferUtils.createIntBuffer(size);
        }
        intArrayBuffer.clear();
        return intArrayBuffer;
    }

    @Override
    public int glGetInteger(int pname) {
        return GL11.glGetInteger(pname);
    }

    @Override
    public String glGetString(int pname) {
        return GL11.glGetString(pname);
    }

    @Override
    public int glGenFramebuffers() {
        return GL30.glGenFramebuffers();
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        GL30.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        GL30.glBindFramebuffer(target, framebuffer);
    }

    @Override
    public int glCheckFramebufferStatus(int target) {
        return GL30.glCheckFramebufferStatus(target);
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    @Override
    public int glCreateShader(int type) {
        return GL20.glCreateShader(type);
    }

    @Override
    public void glCompileShader(int shader) {
        GL20.glCompileShader(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GL20.glGetShaderi(shader, pname);
    }

    @Override
    public String glGetShaderInfoLog(int shader, int maxLength) {
        return GL20.glGetShaderInfoLog(shader, maxLength);
    }

    @Override
    public void glDeleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    @Override
    public int glCreateProgram() {
        return GL20.glCreateProgram();
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
    }

    @Override
    public void glLinkProgram(int program) {
        GL20.glLinkProgram(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GL20.glGetProgrami(program, pname);
    }

    @Override
    public void glUseProgram(int program) {
        GL20.glUseProgram(program);
    }

    @Override
    public void glDeleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GL20.glGetUniformLocation(program, name);
    }

    @Override
    public void glUniform1i(int location, int value) {
        GL20.glUniform1i(location, value);
    }

    @Override
    public int glGetAttribLocation(int program, CharSequence name) {
        return GL20.glGetAttribLocation(program, name);
    }

    @Override
    public void glBindAttribLocation(int program, int index, CharSequence name) {
        GL20.glBindAttribLocation(program, index, name);
    }

    @Override
    public int glGenVertexArrays() {
        return GL30.glGenVertexArrays();
    }

    @Override
    public void glBindVertexArray(int array) {
        GL30.glBindVertexArray(array);
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        GL30.glDeleteVertexArrays(array);
    }

    @Override
    public int glGenBuffers() {
        return org.lwjgl.opengl.GL15.glGenBuffers();
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        org.lwjgl.opengl.GL15.glDeleteBuffers(buffer);
    }

    @Override
    public int glGenTextures() {
        return GL11.glGenTextures();
    }

    @Override
    public void glGenTextures(int[] textures) {
        IntBuffer buffer = getIntArrayBuffer(textures.length);
        GL11.glGenTextures(buffer);
        buffer.get(textures);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GL11.glDeleteTextures(texture);
    }

    @Override
    public void glDeleteTextures(int[] textures) {
        IntBuffer buffer = getIntArrayBuffer(textures.length);
        buffer.put(textures);
        buffer.flip();
        GL11.glDeleteTextures(buffer);
    }

    @Override
    public void glActiveTexture(int texture) {
        GL13.glActiveTexture(texture);
    }

    @Override
    public int glGetTexLevelParameteri(int target, int level, int pname) {
        return GL11.glGetTexLevelParameteri(target, level, pname);
    }

    @Override
    public int glGetTexLevelParameter(int target, int level, int pname) {
        return GL11.glGetTexLevelParameteri(target, level, pname);
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        GL11.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GL11.glPixelStorei(pname, param);
    }

    @Override
    public void enableCullFace() {
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    @Override
    public void disableCullFace() {
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    @Override
    public void enableBlend() {
        GL11.glEnable(GL11.GL_BLEND);
    }

    @Override
    public void disableBlend() {
        GL11.glDisable(GL11.GL_BLEND);
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    @Override
    public void enableDepthTest() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    @Override
    public void disableDepthTest() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    @Override
    public void glDepthFunc(int func) {
        GL11.glDepthFunc(func);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GL11.glColorMask(red, green, blue, alpha);
    }

    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        GL11.glClearColor(red, green, blue, alpha);
    }

    @Override
    public void glClear(int mask) {
        GL11.glClear(mask);
    }

    @Override
    public void clear(int mask, boolean checkError) {
        GL11.glClear(mask);
    }

    @Override
    public void bindTexture(int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    @Override
    public int getActiveTexture() {
        return glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    }

    @Override
    public int getActiveTextureAccessor() {
        return getActiveTexture() - GL13.GL_TEXTURE0;
    }

    @Override
    public int getBoundTexture(int internalUnit) {
        int previous = getActiveTextureAccessor();
        if (previous != internalUnit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + internalUnit);
        }
        int value = glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (previous != internalUnit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + previous);
        }
        return value;
    }

    @Override
    public int getActiveBoundTexture() {
        return glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    }

    @Override
    public int getViewportWidth() {
        return Display.getWidth();
    }

    @Override
    public int getViewportHeight() {
        return Display.getHeight();
    }

    @Override
    public boolean getDepthStateMask() {
        return GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    }

    @Override
    public boolean isBlendEnabled() {
        return GL11.glIsEnabled(GL11.GL_BLEND);
    }

    @Override
    public void setBoundTexture(int unit, int texture) {
        int currentUnit = getActiveTextureAccessor();
        if (currentUnit != unit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        if (currentUnit != unit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + currentUnit);
        }
    }

    @Override
    public int getTextureBinding(int unit) {
        return getBoundTexture(unit);
    }
}
