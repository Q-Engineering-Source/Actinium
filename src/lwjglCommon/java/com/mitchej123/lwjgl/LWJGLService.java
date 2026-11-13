package com.mitchej123.lwjgl;

import java.io.PrintStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public interface LWJGLService {

    boolean isOpenGLVersionSupported(int major, int minor);
    boolean isExtensionSupported(GLExtension extension);
    int getPointerSize();

    int glGenBuffers();
    void glDeleteBuffers(int buffer);
    void glBindBuffer(int target, int buffer);
    void glBufferData(int target, long size, int usage);
    void glBufferData(int target, ByteBuffer data, int usage);
    void glBufferData(int target, long size, long data, int usage);
    void glBufferStorage(int target, long size, int flags);
    ByteBuffer glMapBufferRange(int target, long offset, long length, int flags);
    long nglMapBuffer(int target, int access);
    ByteBuffer glMapBuffer(int target, int access);
    void glUnmapBuffer(int target);
    void glFlushMappedBufferRange(int target, long offset, long length);
    void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size);
    void glBindBufferBase(int target, int index, int buffer);

    int glGenVertexArrays();
    void glDeleteVertexArrays(int array);
    void glBindVertexArray(int array);
    void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);
    void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer);
    void glEnableVertexAttribArray(int index);

    default void glVertexAttribDivisor(int index, int divisor) {
        throw new UnsupportedOperationException("vertex attribute divisors unsupported");
    }

    int glCreateShader(int type);
    void glShaderSource(int shader, CharSequence source);
    void glShaderSourceSafe(int shader, CharSequence source);
    void glCompileShader(int shader);
    String glGetShaderInfoLog(int shader, int maxLength);
    int glGetShaderi(int shader, int pname);
    void glDeleteShader(int shader);

    int glCreateProgram();
    void glAttachShader(int program, int shader);
    void glLinkProgram(int program);
    String glGetProgramInfoLog(int program, int maxLength);
    int glGetProgrami(int program, int pname);
    void glUseProgram(int program);
    void glDeleteProgram(int program);
    void glBindAttribLocation(int program, int index, CharSequence name);
    void glBindFragDataLocation(int program, int colorNumber, CharSequence name);

    int glGetUniformLocation(int program, CharSequence name);
    int glGetUniformBlockIndex(int program, CharSequence name);
    void glUniformBlockBinding(int program, int blockIndex, int blockBinding);
    void glUniform1f(int location, float v0);
    void glUniform1i(int location, int v0);
    void glUniform1fv(int location, FloatBuffer value);
    void glUniform2i(int location, int v0, int v1);
    void glUniform3i(int location, int v0, int v1, int v2);
    void glUniform4i(int location, int v0, int v1, int v2, int v3);
    void glUniform3f(int location, float v0, float v1, float v2);
    void glUniform3fv(int location, FloatBuffer value);
    void glUniform3fv(int location, float[] value);
    void glUniform4fv(int location, FloatBuffer value);
    void glUniform4fv(int location, float[] value);
    void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value);
    void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value);

    void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex);

    default void glDrawArrays(int mode, int first, int count) {
        throw new UnsupportedOperationException("draw arrays unsupported");
    }
    void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex);
    void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride);

    long glFenceSync(int condition, int flags);
    int glClientWaitSync(long sync, int flags, long timeout);
    int glGetSynci(long sync, int pname, IntBuffer length);
    void glWaitSync(long sync, int flags, long timeout);
    void glDeleteSync(long sync);

    int glGenQueries();
    void glDeleteQueries(int query);
    void glQueryCounter(int id, int target);
    long glGetQueryObjectui64(int id, int pname);

    default PrintStream getDebugStream() { return System.err; }
    int setupDebugCallback(DebugMessageHandler handler);
    void disableDebugCallback();
    void glObjectLabel(int identifier, int name, CharSequence label);
    void glPushDebugGroup(int source, int id, CharSequence message);
    void glPopDebugGroup();

    int glGenTextures();
    void glGenTextures(int[] textures);
    void glDeleteTextures(int texture);
    void glDeleteTextures(int[] textures);
    void glBindTexture(int target, int texture);
    void glActiveTexture(int texture);
    int glGetTexLevelParameteri(int target, int level, int pname);
    void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);
    void glPixelStorei(int pname, int param);

    default void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        throw new UnsupportedOperationException("texture image upload unsupported");
    }

    default void glTexParameteri(int target, int pname, int param) {
        throw new UnsupportedOperationException("texture parameters unsupported");
    }
    void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format);

    int glGenFramebuffers();
    void glDeleteFramebuffers(int framebuffer);
    void glBindFramebuffer(int target, int framebuffer);
    int glCheckFramebufferStatus(int target);
    void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level);

    default void glDrawBuffers(int[] buffers) {
        throw new UnsupportedOperationException("draw buffers unsupported");
    }

    default void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        throw new UnsupportedOperationException("framebuffer blits unsupported");
    }

    void glEnable(int cap);
    void glDisable(int cap);
    void glBlendFunc(int sfactor, int dfactor);
    void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
    void glDepthFunc(int func);
    void glDepthMask(boolean flag);
    void glColorMask(boolean red, boolean green, boolean blue, boolean alpha);
    void glViewport(int x, int y, int width, int height);
    void glClear(int mask);
    void glClearColor(float red, float green, float blue, float alpha);
    int glGetError();

    void glMatrixMode(int mode);
    void glLoadMatrixf(FloatBuffer m);

    int glGetInteger(int pname);
    void glGetIntegerv(int pname, int[] params);
    boolean glGetBoolean(int pname);
    String glGetString(int pname);
    int glGetAttribLocation(int program, CharSequence name);

    MemoryStack stackPush();

    long nmemAlloc(long size);
    long nmemCalloc(long count, long size);
    long nmemAlignedAlloc(long alignment, long size);
    long nmemRealloc(long ptr, long size);
    void nmemFree(long ptr);
    void nmemAlignedFree(long ptr);

    ByteBuffer memAlloc(int size);
    ByteBuffer memCalloc(int size);
    ByteBuffer memRealloc(ByteBuffer buffer, int size);
    void memFree(Buffer buffer);
    ByteBuffer memByteBuffer(long address, int capacity);
    long memAddress(Buffer buffer);
    long memAddress(Buffer buffer, int position);

    void memSet(long address, int value, long bytes);
    void memCopy(long src, long dst, long bytes);

    default void memCopy(ByteBuffer src, ByteBuffer dst) {
        memCopy(memAddress(src), memAddress(dst), src.remaining());
    }

    void memPutByte(long address, byte value);
    void memPutShort(long address, short value);
    void memPutInt(long address, int value);
    void memPutFloat(long address, float value);
    void memPutLong(long address, long value);
    void memPutAddress(long address, long value);

    byte memGetByte(long address);
    short memGetShort(long address);
    int memGetInt(long address);
    float memGetFloat(long address);
    long memGetLong(long address);
    long memGetAddress(long address);
    ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity);
}
