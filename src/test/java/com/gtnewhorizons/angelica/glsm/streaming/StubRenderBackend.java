package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Test double for {@link RenderBackend}: every GL call throws {@link UnsupportedOperationException}
 * except the handful of buffer-mapping hooks recorded below. Used by {@link StreamingUploaderTest}.
 */
class StubRenderBackend extends RenderBackend {

    /** Returned by {@link #mapBufferRange}; {@code null} simulates a driver-side mapping failure. */
    ByteBuffer mapBufferRangeResult;
    int bufferDataCalls;
    ByteBuffer lastBufferData;
    int unmapBufferCalls;

    @Override public ByteBuffer mapBufferRange(int target, long offset, long length, int access) { return mapBufferRangeResult; }
    @Override public void bufferData(int target, ByteBuffer data, int usage) { bufferDataCalls++; lastBufferData = data; }
    @Override public boolean unmapBuffer(int target) { unmapBufferCalls++; return true; }

    @Override public void init() { throw new UnsupportedOperationException(); }
    @Override public void shutdown() { throw new UnsupportedOperationException(); }
    @Override public boolean isAvailable() { throw new UnsupportedOperationException(); }
    @Override public String getName() { throw new UnsupportedOperationException(); }
    @Override public boolean hasContext() { throw new UnsupportedOperationException(); }
    @Override public int getMinGLSLVersion() { throw new UnsupportedOperationException(); }
    @Override public void flush() { throw new UnsupportedOperationException(); }
    @Override public void finish() { throw new UnsupportedOperationException(); }
    @Override public void enable(int cap) { throw new UnsupportedOperationException(); }
    @Override public void enablei(int cap, int index) { throw new UnsupportedOperationException(); }
    @Override public void disable(int cap) { throw new UnsupportedOperationException(); }
    @Override public void disablei(int cap, int index) { throw new UnsupportedOperationException(); }
    @Override public void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { throw new UnsupportedOperationException(); }
    @Override public void blendFunc(int sfactor, int dfactor) { throw new UnsupportedOperationException(); }
    @Override public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { throw new UnsupportedOperationException(); }
    @Override public void blendEquation(int mode) { throw new UnsupportedOperationException(); }
    @Override public void blendEquationSeparate(int modeRGB, int modeAlpha) { throw new UnsupportedOperationException(); }
    @Override public void blendColor(float red, float green, float blue, float alpha) { throw new UnsupportedOperationException(); }
    @Override public void depthFunc(int func) { throw new UnsupportedOperationException(); }
    @Override public void depthMask(boolean flag) { throw new UnsupportedOperationException(); }
    @Override public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) { throw new UnsupportedOperationException(); }
    @Override public void cullFace(int mode) { throw new UnsupportedOperationException(); }
    @Override public void frontFace(int mode) { throw new UnsupportedOperationException(); }
    @Override public void polygonMode(int face, int mode) { throw new UnsupportedOperationException(); }
    @Override public void polygonOffset(float factor, float units) { throw new UnsupportedOperationException(); }
    @Override public void stencilFunc(int func, int ref, int mask) { throw new UnsupportedOperationException(); }
    @Override public void stencilOp(int sfail, int dpfail, int dppass) { throw new UnsupportedOperationException(); }
    @Override public void stencilMask(int mask) { throw new UnsupportedOperationException(); }
    @Override public void stencilFuncSeparate(int face, int func, int ref, int mask) { throw new UnsupportedOperationException(); }
    @Override public void stencilOpSeparate(int face, int sfail, int dpfail, int dppass) { throw new UnsupportedOperationException(); }
    @Override public void stencilMaskSeparate(int face, int mask) { throw new UnsupportedOperationException(); }
    @Override public void viewport(int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void depthRange(double nearVal, double farVal) { throw new UnsupportedOperationException(); }
    @Override public void scissor(int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void clearColor(float red, float green, float blue, float alpha) { throw new UnsupportedOperationException(); }
    @Override public void clearDepth(double depth) { throw new UnsupportedOperationException(); }
    @Override public void clearStencil(int s) { throw new UnsupportedOperationException(); }
    @Override public void clear(int mask) { throw new UnsupportedOperationException(); }
    @Override public void lineWidth(float width) { throw new UnsupportedOperationException(); }
    @Override public void pointSize(float size) { throw new UnsupportedOperationException(); }
    @Override public void logicOp(int opcode) { throw new UnsupportedOperationException(); }
    @Override public void hint(int target, int mode) { throw new UnsupportedOperationException(); }
    @Override public void drawArrays(int mode, int first, int count) { throw new UnsupportedOperationException(); }
    @Override public void drawElements(int mode, int count, int type, long indices) { throw new UnsupportedOperationException(); }
    @Override public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) { throw new UnsupportedOperationException(); }
    @Override public void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) { throw new UnsupportedOperationException(); }
    @Override public void drawElementsInstanced(int mode, int count, int type, long indices, int primcount) { throw new UnsupportedOperationException(); }
    @Override public void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex) { throw new UnsupportedOperationException(); }
    @Override public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) { throw new UnsupportedOperationException(); }
    @Override public void drawBuffer(int mode) { throw new UnsupportedOperationException(); }
    @Override public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) { throw new UnsupportedOperationException(); }
    @Override public void dispatchComputeIndirect(long offset) { throw new UnsupportedOperationException(); }
    @Override public int genTextures() { throw new UnsupportedOperationException(); }
    @Override public void genTextures(IntBuffer textures) { throw new UnsupportedOperationException(); }
    @Override public void deleteTextures(int texture) { throw new UnsupportedOperationException(); }
    @Override public void bindTexture(int target, int texture) { throw new UnsupportedOperationException(); }
    @Override public void activeTexture(int texture) { throw new UnsupportedOperationException(); }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, DoubleBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixelBufferOffset) { throw new UnsupportedOperationException(); }
    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void texParameteri(int target, int pname, int param) { throw new UnsupportedOperationException(); }
    @Override public void texParameterf(int target, int pname, float param) { throw new UnsupportedOperationException(); }
    @Override public void texParameteriv(int target, int pname, IntBuffer params) { throw new UnsupportedOperationException(); }
    @Override public void texParameterfv(int target, int pname, FloatBuffer params) { throw new UnsupportedOperationException(); }
    @Override public int getTexParameteri(int target, int pname) { throw new UnsupportedOperationException(); }
    @Override public float getTexParameterf(int target, int pname) { throw new UnsupportedOperationException(); }
    @Override public int getTexLevelParameteri(int target, int level, int pname) { throw new UnsupportedOperationException(); }
    @Override public void generateMipmap(int target) { throw new UnsupportedOperationException(); }
    @Override public void pixelStorei(int pname, int param) { throw new UnsupportedOperationException(); }
    @Override public int genSamplers() { throw new UnsupportedOperationException(); }
    @Override public void deleteSamplers(int sampler) { throw new UnsupportedOperationException(); }
    @Override public void bindSampler(int unit, int sampler) { throw new UnsupportedOperationException(); }
    @Override public void samplerParameteri(int sampler, int pname, int param) { throw new UnsupportedOperationException(); }
    @Override public void samplerParameterf(int sampler, int pname, float param) { throw new UnsupportedOperationException(); }
    @Override public int genFramebuffers() { throw new UnsupportedOperationException(); }
    @Override public void deleteFramebuffers(int framebuffer) { throw new UnsupportedOperationException(); }
    @Override public void bindFramebuffer(int target, int framebuffer) { throw new UnsupportedOperationException(); }
    @Override public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { throw new UnsupportedOperationException(); }
    @Override public void framebufferTexture(int target, int attachment, int texture, int level) { throw new UnsupportedOperationException(); }
    @Override public int checkFramebufferStatus(int target) { throw new UnsupportedOperationException(); }
    @Override public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { throw new UnsupportedOperationException(); }
    @Override public void drawBuffers(int buffer) { throw new UnsupportedOperationException(); }
    @Override public void drawBuffers(IntBuffer bufs) { throw new UnsupportedOperationException(); }
    @Override public void readBuffer(int mode) { throw new UnsupportedOperationException(); }
    @Override public void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void getTexImage(int target, int level, int format, int type, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void getTexImage(int target, int level, int format, int type, IntBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public int getFramebufferAttachmentParameteri(int target, int attachment, int pname) { throw new UnsupportedOperationException(); }
    @Override public int createShader(int type) { throw new UnsupportedOperationException(); }
    @Override public void deleteShader(int shader) { throw new UnsupportedOperationException(); }
    @Override public void shaderSource(int shader, CharSequence source) { throw new UnsupportedOperationException(); }
    @Override public void compileShader(int shader) { throw new UnsupportedOperationException(); }
    @Override public int createProgram() { throw new UnsupportedOperationException(); }
    @Override public void deleteProgram(int program) { throw new UnsupportedOperationException(); }
    @Override public void attachShader(int program, int shader) { throw new UnsupportedOperationException(); }
    @Override public void detachShader(int program, int shader) { throw new UnsupportedOperationException(); }
    @Override public void linkProgram(int program) { throw new UnsupportedOperationException(); }
    @Override public void useProgram(int program) { throw new UnsupportedOperationException(); }
    @Override public String getShaderInfoLog(int shader, int maxLength) { throw new UnsupportedOperationException(); }
    @Override public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) { throw new UnsupportedOperationException(); }
    @Override public String getProgramInfoLog(int program, int maxLength) { throw new UnsupportedOperationException(); }
    @Override public void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) { throw new UnsupportedOperationException(); }
    @Override public int getShaderi(int shader, int pname) { throw new UnsupportedOperationException(); }
    @Override public int getProgrami(int program, int pname) { throw new UnsupportedOperationException(); }
    @Override public void getProgramiv(int program, int pname, IntBuffer params) { throw new UnsupportedOperationException(); }
    @Override public String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) { throw new UnsupportedOperationException(); }
    @Override public void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) { throw new UnsupportedOperationException(); }
    @Override public void bindAttribLocation(int program, int index, CharSequence name) { throw new UnsupportedOperationException(); }
    @Override public int getAttribLocation(int program, CharSequence name) { throw new UnsupportedOperationException(); }
    @Override public int getAttribLocation(int program, ByteBuffer name) { throw new UnsupportedOperationException(); }
    @Override public int getUniformLocation(int program, CharSequence name) { throw new UnsupportedOperationException(); }
    @Override public int getUniformLocation(int program, ByteBuffer name) { throw new UnsupportedOperationException(); }
    @Override public void getShaderSource(int shader, IntBuffer length, ByteBuffer source) { throw new UnsupportedOperationException(); }
    @Override public void uniform1i(int location, int v0) { throw new UnsupportedOperationException(); }
    @Override public void uniform1f(int location, float v0) { throw new UnsupportedOperationException(); }
    @Override public void uniform2f(int location, float v0, float v1) { throw new UnsupportedOperationException(); }
    @Override public void uniform2i(int location, int v0, int v1) { throw new UnsupportedOperationException(); }
    @Override public void uniform3f(int location, float v0, float v1, float v2) { throw new UnsupportedOperationException(); }
    @Override public void uniform3i(int location, int v0, int v1, int v2) { throw new UnsupportedOperationException(); }
    @Override public void uniform4f(int location, float v0, float v1, float v2, float v3) { throw new UnsupportedOperationException(); }
    @Override public void uniform4i(int location, int v0, int v1, int v2, int v3) { throw new UnsupportedOperationException(); }
    @Override public void uniform3(int location, FloatBuffer value) { throw new UnsupportedOperationException(); }
    @Override public void uniform4(int location, FloatBuffer value) { throw new UnsupportedOperationException(); }
    @Override public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) { throw new UnsupportedOperationException(); }
    @Override public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttrib2f(int index, float v0, float v1) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttrib3f(int index, float v0, float v1, float v2) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) { throw new UnsupportedOperationException(); }
    @Override public int genBuffers() { throw new UnsupportedOperationException(); }
    @Override public void deleteBuffers(int buffer) { throw new UnsupportedOperationException(); }
    @Override public void deleteBuffers(IntBuffer buffers) { throw new UnsupportedOperationException(); }
    @Override public void bindBuffer(int target, int buffer) { throw new UnsupportedOperationException(); }
    @Override public void bindBufferBase(int target, int index, int buffer) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, long size, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, FloatBuffer data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, ShortBuffer data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, IntBuffer data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, DoubleBuffer data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, int[] data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferData(int target, float[] data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void bufferSubData(int target, long offset, ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void bufferSubData(int target, long offset, ShortBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void bufferSubData(int target, long offset, IntBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void bufferSubData(int target, long offset, FloatBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void bufferSubData(int target, long offset, DoubleBuffer data) { throw new UnsupportedOperationException(); }
    @Override public ByteBuffer mapBuffer(int target, int access) { throw new UnsupportedOperationException(); }
    @Override public ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer old_buffer) { throw new UnsupportedOperationException(); }
    @Override public void bufferStorage(int target, ByteBuffer data, int flags) { throw new UnsupportedOperationException(); }
    @Override public void bufferStorage(int target, long size, int flags) { throw new UnsupportedOperationException(); }
    @Override public void getBufferSubData(int target, long offset, ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void getBufferSubData(int target, long offset, ShortBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void getBufferSubData(int target, long offset, IntBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void getBufferSubData(int target, long offset, FloatBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void getBufferSubData(int target, long offset, DoubleBuffer data) { throw new UnsupportedOperationException(); }
    @Override public int getBufferParameteri(int target, int pname) { throw new UnsupportedOperationException(); }
    @Override public boolean isBuffer(int buffer) { throw new UnsupportedOperationException(); }
    @Override public int genVertexArrays() { throw new UnsupportedOperationException(); }
    @Override public long getContextHandle() { return 0L; }
    @Override public void deleteVertexArrays(int array) { throw new UnsupportedOperationException(); }
    @Override public void bindVertexArray(int array) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer) { throw new UnsupportedOperationException(); }
    @Override public void enableVertexAttribArray(int index) { throw new UnsupportedOperationException(); }
    @Override public void disableVertexAttribArray(int index) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttribDivisor(int index, int divisor) { throw new UnsupportedOperationException(); }
    @Override public void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) { throw new UnsupportedOperationException(); }
    @Override public void vertexAttribBinding(int attribindex, int bindingindex) { throw new UnsupportedOperationException(); }
    @Override public int createTextures(int target) { throw new UnsupportedOperationException(); }
    @Override public void bindTextureUnit(int unit, int texture) { throw new UnsupportedOperationException(); }
    @Override public void textureParameteri(int texture, int target, int pname, int param) { throw new UnsupportedOperationException(); }
    @Override public void textureParameterf(int texture, int target, int pname, float param) { throw new UnsupportedOperationException(); }
    @Override public void textureParameteriv(int texture, int target, int pname, IntBuffer params) { throw new UnsupportedOperationException(); }
    @Override public void texStorage1D(int target, int levels, int internalFormat, int width) { throw new UnsupportedOperationException(); }
    @Override public void texStorage2D(int target, int levels, int internalFormat, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void texStorage3D(int target, int levels, int internalFormat, int width, int height, int depth) { throw new UnsupportedOperationException(); }
    @Override public void compressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void compressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int imageSize, long data) { throw new UnsupportedOperationException(); }
    @Override public void textureStorage1D(int texture, int levels, int internalFormat, int width) { throw new UnsupportedOperationException(); }
    @Override public void textureStorage2D(int texture, int levels, int internalFormat, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void textureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) { throw new UnsupportedOperationException(); }
    @Override public void generateTextureMipmap(int texture) { throw new UnsupportedOperationException(); }
    @Override public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) { throw new UnsupportedOperationException(); }
    @Override public int createFramebuffers() { throw new UnsupportedOperationException(); }
    @Override public void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) { throw new UnsupportedOperationException(); }
    @Override public void namedFramebufferReadBuffer(int framebuffer, int mode) { throw new UnsupportedOperationException(); }
    @Override public void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs) { throw new UnsupportedOperationException(); }
    @Override public void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { throw new UnsupportedOperationException(); }
    @Override public int createBuffers() { throw new UnsupportedOperationException(); }
    @Override public void namedBufferData(int buffer, ByteBuffer data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void namedBufferData(int buffer, FloatBuffer data, int usage) { throw new UnsupportedOperationException(); }
    @Override public void namedBufferSubData(int buffer, long offset, ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public int getTextureParameteri(int texture, int target, int pname) { throw new UnsupportedOperationException(); }
    @Override public float getTextureParameterf(int texture, int target, int pname) { throw new UnsupportedOperationException(); }
    @Override public int getTextureLevelParameteri(int texture, int level, int pname) { throw new UnsupportedOperationException(); }
    @Override public int getInteger(int pname) { throw new UnsupportedOperationException(); }
    @Override public void getInteger(int pname, IntBuffer params) { throw new UnsupportedOperationException(); }
    @Override public int getIntegerIndexed(int pname, int index) { throw new UnsupportedOperationException(); }
    @Override public float getFloat(int pname) { throw new UnsupportedOperationException(); }
    @Override public void getFloat(int pname, FloatBuffer params) { throw new UnsupportedOperationException(); }
    @Override public boolean getBoolean(int pname) { throw new UnsupportedOperationException(); }
    @Override public void getBoolean(int pname, ByteBuffer params) { throw new UnsupportedOperationException(); }
    @Override public String getString(int pname) { throw new UnsupportedOperationException(); }
    @Override public String getStringi(int name, int index) { throw new UnsupportedOperationException(); }
    @Override public int getError() { throw new UnsupportedOperationException(); }
    @Override public long fenceSync(int condition, int flags) { throw new UnsupportedOperationException(); }
    @Override public int clientWaitSync(long sync, int flags, long timeout) { throw new UnsupportedOperationException(); }
    @Override public void deleteSync(long sync) { throw new UnsupportedOperationException(); }
    @Override public void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void clearTexImage(int texture, int level, int format, int type) { throw new UnsupportedOperationException(); }
    @Override public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) { throw new UnsupportedOperationException(); }
    @Override public void memoryBarrier(int barriers) { throw new UnsupportedOperationException(); }
    @Override public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int srcWidth, int srcHeight, int srcDepth) { throw new UnsupportedOperationException(); }
    @Override public void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) { throw new UnsupportedOperationException(); }
    @Override public void primitiveRestartIndex(int index) { throw new UnsupportedOperationException(); }
    @Override public void pointParameterf(int pname, float param) { throw new UnsupportedOperationException(); }
    @Override public void pointParameteri(int pname, int param) { throw new UnsupportedOperationException(); }
    @Override public int genRenderbuffers() { throw new UnsupportedOperationException(); }
    @Override public void deleteRenderbuffers(int renderbuffer) { throw new UnsupportedOperationException(); }
    @Override public void bindRenderbuffer(int target, int renderbuffer) { throw new UnsupportedOperationException(); }
    @Override public void renderbufferStorage(int target, int internalformat, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) { throw new UnsupportedOperationException(); }
    @Override public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) { throw new UnsupportedOperationException(); }
    @Override public void getTexImage(int target, int level, int format, int type, long pixelBufferOffset) { throw new UnsupportedOperationException(); }
    @Override public void getActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) { throw new UnsupportedOperationException(); }
    @Override public String getActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) { throw new UnsupportedOperationException(); }
    @Override public void bindBufferRange(int target, int index, int buffer, long offset, long size) { throw new UnsupportedOperationException(); }
    @Override public int getUniformBlockIndex(int program, CharSequence name) { throw new UnsupportedOperationException(); }
    @Override public void uniformBlockBinding(int program, int blockIndex, int binding) { throw new UnsupportedOperationException(); }
    @Override public int getIndexedBufferBinding(int target, int index) { throw new UnsupportedOperationException(); }
    @Override public void flushMappedBufferRange(int target, long offset, long length) { throw new UnsupportedOperationException(); }
    @Override public boolean isTexture(int texture) { throw new UnsupportedOperationException(); }
    @Override public boolean isFramebuffer(int framebuffer) { throw new UnsupportedOperationException(); }
    @Override public boolean isRenderbuffer(int renderbuffer) { throw new UnsupportedOperationException(); }
    @Override public boolean isSampler(int sampler) { throw new UnsupportedOperationException(); }
    @Override public boolean isQuery(int query) { throw new UnsupportedOperationException(); }
    @Override public void namedBufferData(int buffer, long size, int usage) { throw new UnsupportedOperationException(); }
    @Override public void clearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) { throw new UnsupportedOperationException(); }
}
