package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.device.MemoryStackWrapper;
import com.gtnewhorizons.angelica.sdlgpu.util.MemoryAccess;
import com.mitchej123.lwjgl.DebugMessageHandler;
import com.mitchej123.lwjgl.GLExtension;
import com.mitchej123.lwjgl.LWJGLService;
import com.mitchej123.lwjgl.LWJGLServiceProvider;
import com.mitchej123.lwjgl.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * SDL GPU implementation of {@link LWJGLService} for Celeritas/Embeddium. Routes GL calls through
 * {@link BackendManager#RENDER_BACKEND} (the SDL GPU backend when active) instead of the real GL
 * context, which does not exist in SDL GPU mode (the fabricated GLCapabilities only contain
 * sentinel function pointers).
 *
 * <p>{@link LWJGLServiceProvider} instantiates this class (through {@link #create()}) only when the
 * selected render backend reports {@code isSDLGPU()}, so every Embeddium GL object created after
 * backend selection is served by the SDL backend rather than by the sentinel GL function pointers.
 * Unlike the upstream lwjgl3ify integration this needs no separate armed flag: the backend choice
 * itself is the switch.</p>
 */
public final class SDLGPULWJGLService implements LWJGLService {

    /** Factory used by {@link LWJGLServiceProvider}; see the class comment for the selection rule. */
    public static SDLGPULWJGLService create() {
        return new SDLGPULWJGLService();
    }

    @Override public boolean isOpenGLVersionSupported(int major, int minor) {
        return major < 4 || (major == 4 && minor <= 6);
    }

    @Override public boolean isExtensionSupported(GLExtension extension) {
        return switch (extension) {
            case ARB_buffer_storage,
                 ARB_map_buffer_range,
                 ARB_copy_buffer,
                 ARB_multi_draw_indirect,
                 ARB_draw_elements_base_vertex,
                 ARB_sync,
                 ARB_uniform_buffer_object,
                 ARB_vertex_array_object,
                 ARB_shader_storage_buffer_object,
                 KHR_debug
                 -> true;
            case ARB_timer_query,
                 ARB_debug_output,
                 AMD_debug_output,
                 ARB_compatibility,
                 ARB_direct_state_access,
                 ARB_texture_storage,
                 ARB_base_instance
                 -> false;
        };
    }

    @Override public int getPointerSize() { return 8; } // 64-bit

    @Override public int glGenBuffers() { return BackendManager.RENDER_BACKEND.genBuffers(); }
    @Override public void glDeleteBuffers(int buffer) { BackendManager.RENDER_BACKEND.deleteBuffers(buffer); }
    @Override public void glBindBuffer(int target, int buffer) { GLStateManager.glBindBuffer(target, buffer); }
    @Override public void glBufferData(int target, long size, int usage) { BackendManager.RENDER_BACKEND.bufferData(target, size, usage); }
    @Override public void glBufferData(int target, ByteBuffer data, int usage) { BackendManager.RENDER_BACKEND.bufferData(target, data, usage); }
    @Override public void glBufferData(int target, long size, long data, int usage) {
        if (data != 0) {
            BackendManager.RENDER_BACKEND.bufferData(target, MemoryUtil.memByteBuffer(data, (int) size), usage);
        } else {
            BackendManager.RENDER_BACKEND.bufferData(target, size, usage);
        }
    }
    @Override public void glBufferStorage(int target, long size, int flags) { BackendManager.RENDER_BACKEND.bufferStorage(target, size, flags); }
    @Override public ByteBuffer glMapBufferRange(int target, long offset, long length, int flags) { return BackendManager.RENDER_BACKEND.mapBufferRange(target, offset, length, flags); }
    @Override public long nglMapBuffer(int target, int access) {
        final ByteBuffer mapped = BackendManager.RENDER_BACKEND.mapBuffer(target, access);
        // GL semantics: a null pointer means the map failed. The backend returns null when the
        // bound buffer has no known size, which is the only recoverable failure it reports.
        return mapped == null ? 0L : MemoryUtil.memAddress(mapped);
    }
    @Override public ByteBuffer glMapBuffer(int target, int access) { return BackendManager.RENDER_BACKEND.mapBuffer(target, access); }
    @Override public void glUnmapBuffer(int target) { BackendManager.RENDER_BACKEND.unmapBuffer(target); }
    @Override public void glFlushMappedBufferRange(int target, long offset, long length) { BackendManager.RENDER_BACKEND.flushMappedBufferRange(target, offset, length); }
    @Override public void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) { BackendManager.RENDER_BACKEND.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size); }
    @Override public void glBindBufferBase(int target, int index, int buffer) { BackendManager.RENDER_BACKEND.bindBufferBase(target, index, buffer); }

    @Override public int glGenVertexArrays() { return BackendManager.RENDER_BACKEND.genVertexArrays(); }
    @Override public void glDeleteVertexArrays(int array) { BackendManager.RENDER_BACKEND.deleteVertexArrays(array); }
    @Override public void glBindVertexArray(int array) { GLStateManager.glBindVertexArray(array); }
    @Override public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) { BackendManager.RENDER_BACKEND.vertexAttribPointer(index, size, type, normalized, stride, pointer); }
    @Override public void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) { BackendManager.RENDER_BACKEND.vertexAttribIPointer(index, size, type, stride, pointer); }
    @Override public void glEnableVertexAttribArray(int index) { BackendManager.RENDER_BACKEND.enableVertexAttribArray(index); }

    @Override public int glCreateShader(int type) { return BackendManager.RENDER_BACKEND.createShader(type); }
    @Override public void glShaderSource(int shader, CharSequence source) { BackendManager.RENDER_BACKEND.shaderSource(shader, source); }
    @Override public void glShaderSourceSafe(int shader, CharSequence source) { BackendManager.RENDER_BACKEND.shaderSource(shader, source); }
    @Override public void glCompileShader(int shader) { BackendManager.RENDER_BACKEND.compileShader(shader); }
    @Override public String glGetShaderInfoLog(int shader, int maxLength) { return BackendManager.RENDER_BACKEND.getShaderInfoLog(shader, maxLength); }
    @Override public int glGetShaderi(int shader, int pname) { return BackendManager.RENDER_BACKEND.getShaderi(shader, pname); }
    @Override public void glDeleteShader(int shader) { BackendManager.RENDER_BACKEND.deleteShader(shader); }

    @Override public int glCreateProgram() { return BackendManager.RENDER_BACKEND.createProgram(); }
    @Override public void glAttachShader(int program, int shader) { BackendManager.RENDER_BACKEND.attachShader(program, shader); }
    @Override public void glLinkProgram(int program) { BackendManager.RENDER_BACKEND.linkProgram(program); }
    @Override public String glGetProgramInfoLog(int program, int maxLength) { return BackendManager.RENDER_BACKEND.getProgramInfoLog(program, maxLength); }
    @Override public int glGetProgrami(int program, int pname) { return BackendManager.RENDER_BACKEND.getProgrami(program, pname); }
    @Override public void glUseProgram(int program) { GLStateManager.glUseProgram(program); }
    @Override public void glDeleteProgram(int program) { BackendManager.RENDER_BACKEND.deleteProgram(program); }
    @Override public void glBindAttribLocation(int program, int index, CharSequence name) { BackendManager.RENDER_BACKEND.bindAttribLocation(program, index, name); }
    @Override public void glBindFragDataLocation(int program, int colorNumber, CharSequence name) { BackendManager.RENDER_BACKEND.bindFragDataLocation(program, colorNumber, name); }

    @Override public int glGetUniformLocation(int program, CharSequence name) { return BackendManager.RENDER_BACKEND.getUniformLocation(program, name); }
    @Override public int glGetUniformBlockIndex(int program, CharSequence name) { return BackendManager.RENDER_BACKEND.getUniformBlockIndex(program, name); }
    @Override public void glUniformBlockBinding(int program, int blockIndex, int blockBinding) { BackendManager.RENDER_BACKEND.uniformBlockBinding(program, blockIndex, blockBinding); }
    @Override public void glUniform1f(int location, float v0) { BackendManager.RENDER_BACKEND.uniform1f(location, v0); }
    @Override public void glUniform1i(int location, int v0) { BackendManager.RENDER_BACKEND.uniform1i(location, v0); }
    @Override public void glUniform1fv(int location, FloatBuffer value) { BackendManager.RENDER_BACKEND.uniform1fv(location, value); }
    @Override public void glUniform2i(int location, int v0, int v1) { BackendManager.RENDER_BACKEND.uniform2i(location, v0, v1); }
    @Override public void glUniform3i(int location, int v0, int v1, int v2) { BackendManager.RENDER_BACKEND.uniform3i(location, v0, v1, v2); }
    @Override public void glUniform4i(int location, int v0, int v1, int v2, int v3) { BackendManager.RENDER_BACKEND.uniform4i(location, v0, v1, v2, v3); }
    @Override public void glUniform3f(int location, float v0, float v1, float v2) { BackendManager.RENDER_BACKEND.uniform3f(location, v0, v1, v2); }
    @Override public void glUniform3fv(int location, FloatBuffer value) { BackendManager.RENDER_BACKEND.uniform3(location, value); }
    @Override public void glUniform3fv(int location, float[] value) { BackendManager.RENDER_BACKEND.uniform3fv(location, value); }
    @Override public void glUniform4fv(int location, FloatBuffer value) { BackendManager.RENDER_BACKEND.uniform4(location, value); }
    @Override public void glUniform4fv(int location, float[] value) { BackendManager.RENDER_BACKEND.uniform4fv(location, value); }
    @Override public void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value) { BackendManager.RENDER_BACKEND.uniformMatrix3(location, transpose, value); }
    @Override public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) { BackendManager.RENDER_BACKEND.uniformMatrix4(location, transpose, value); }

    @Override public void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex) { BackendManager.RENDER_BACKEND.drawElementsBaseVertex(mode, count, type, indices, basevertex); }
    @Override public void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) { BackendManager.RENDER_BACKEND.multiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex); }
    @Override public void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) { BackendManager.RENDER_BACKEND.multiDrawElementsIndirect(mode, type, indirect, drawcount, stride); }

    @Override public long glFenceSync(int condition, int flags) { return BackendManager.RENDER_BACKEND.fenceSync(condition, flags); }
    @Override public int glClientWaitSync(long sync, int flags, long timeout) { return BackendManager.RENDER_BACKEND.clientWaitSync(sync, flags, timeout); }
    @Override public int glGetSynci(long sync, int pname, IntBuffer length) {
        // GL semantics: the IntBuffer receives the number of values written (1). The SDL backend's
        // getSynci writes the status value into the buffer instead, so write the count here and
        // read the status directly, matching the upstream SDLGPU LWJGL service.
        if (length != null && length.remaining() > 0) length.put(length.position(), 1);
        final var backend = BackendManager.RENDER_BACKEND;
        if (backend instanceof SDLGPURenderBackend sdl) {
            return sdl.getSyncStatus(sync);
        }
        return backend.getSynci(sync, pname, length);
    }
    @Override public void glWaitSync(long sync, int flags, long timeout) { BackendManager.RENDER_BACKEND.waitSync(sync, flags, timeout); }
    @Override public void glDeleteSync(long sync) { BackendManager.RENDER_BACKEND.deleteSync(sync); }

    @Override public int glGenQueries() { return BackendManager.RENDER_BACKEND.genQueries(); }
    @Override public void glDeleteQueries(int query) { BackendManager.RENDER_BACKEND.deleteQueries(query); }
    @Override public void glQueryCounter(int id, int target) { BackendManager.RENDER_BACKEND.queryCounter(id, target); }
    @Override public long glGetQueryObjectui64(int id, int pname) { return BackendManager.RENDER_BACKEND.getQueryObjectui64(id, pname); }

    @Override public int setupDebugCallback(DebugMessageHandler handler) { return 0; }
    @Override public void disableDebugCallback() { }
    @Override public void glObjectLabel(int identifier, int name, CharSequence label) { BackendManager.RENDER_BACKEND.objectLabel(identifier, name, label); }
    @Override public void glPushDebugGroup(int source, int id, CharSequence message) { BackendManager.RENDER_BACKEND.pushDebugGroup(source, id, message); }
    @Override public void glPopDebugGroup() { BackendManager.RENDER_BACKEND.popDebugGroup(); }

    @Override public int glGenTextures() { return BackendManager.RENDER_BACKEND.genTextures(); }
    @Override public void glGenTextures(int[] textures) {
        for (int i = 0; i < textures.length; i++) textures[i] = BackendManager.RENDER_BACKEND.genTextures();
    }
    @Override public void glDeleteTextures(int texture) { BackendManager.RENDER_BACKEND.deleteTextures(texture); }
    @Override public void glDeleteTextures(int[] textures) {
        for (int t : textures) BackendManager.RENDER_BACKEND.deleteTextures(t);
    }
    @Override public void glBindTexture(int target, int texture) { GLStateManager.glBindTexture(target, texture); }
    @Override public void glActiveTexture(int texture) { GLStateManager.glActiveTexture(texture); }
    @Override public int glGetTexLevelParameteri(int target, int level, int pname) { return BackendManager.RENDER_BACKEND.getTexLevelParameteri(target, level, pname); }
    @Override public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { BackendManager.RENDER_BACKEND.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height); }
    @Override public void glPixelStorei(int pname, int param) { BackendManager.RENDER_BACKEND.pixelStorei(pname, param); }
    @Override public void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) { GLStateManager.glBindImageTexture(unit, texture, level, layered, layer, access, format); }

    @Override public int glGenFramebuffers() { return BackendManager.RENDER_BACKEND.genFramebuffers(); }
    @Override public void glDeleteFramebuffers(int framebuffer) { BackendManager.RENDER_BACKEND.deleteFramebuffers(framebuffer); }
    @Override public void glBindFramebuffer(int target, int framebuffer) { GLStateManager.glBindFramebuffer(target, framebuffer); }
    @Override public int glCheckFramebufferStatus(int target) { return BackendManager.RENDER_BACKEND.checkFramebufferStatus(target); }
    @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { BackendManager.RENDER_BACKEND.framebufferTexture2D(target, attachment, textarget, texture, level); }

    @Override public void glEnable(int cap) { GLStateManager.glEnable(cap); }
    @Override public void glDisable(int cap) { GLStateManager.glDisable(cap); }
    @Override public void glBlendFunc(int sfactor, int dfactor) { GLStateManager.glBlendFunc(sfactor, dfactor); }
    @Override public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { GLStateManager.tryBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }
    @Override public void glDepthFunc(int func) { GLStateManager.glDepthFunc(func); }
    @Override public void glDepthMask(boolean flag) { GLStateManager.glDepthMask(flag); }
    @Override public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) { GLStateManager.glColorMask(red, green, blue, alpha); }
    @Override public void glViewport(int x, int y, int width, int height) { GLStateManager.glViewport(x, y, width, height); }
    @Override public void glClear(int mask) { BackendManager.RENDER_BACKEND.clear(mask); }
    @Override public void glClearColor(float red, float green, float blue, float alpha) { BackendManager.RENDER_BACKEND.clearColor(red, green, blue, alpha); }
    @Override public int glGetError() { return BackendManager.RENDER_BACKEND.getError(); }

    @Override public void glMatrixMode(int mode) { /* Handled by GLSM FFP emulation */ }
    @Override public void glLoadMatrixf(FloatBuffer m) { /* Handled by GLSM FFP emulation */ }

    @Override public int glGetInteger(int pname) { return BackendManager.RENDER_BACKEND.getInteger(pname); }
    @Override public void glGetIntegerv(int pname, int[] params) {
        if (params.length > 0) params[0] = BackendManager.RENDER_BACKEND.getInteger(pname);
    }
    @Override public boolean glGetBoolean(int pname) { return BackendManager.RENDER_BACKEND.getBoolean(pname); }
    @Override public String glGetString(int pname) { return BackendManager.RENDER_BACKEND.getString(pname); }
    @Override public int glGetAttribLocation(int program, CharSequence name) { return BackendManager.RENDER_BACKEND.getAttribLocation(program, name); }

    @Override public MemoryStack stackPush() {
        return new MemoryStackWrapper(org.lwjgl.system.MemoryStack.stackPush());
    }

    @Override public long nmemAlloc(long size) { return MemoryUtil.nmemAlloc(size); }
    @Override public long nmemCalloc(long count, long size) { return MemoryUtil.nmemCalloc(count, size); }
    @Override public long nmemAlignedAlloc(long alignment, long size) { return MemoryUtil.nmemAlignedAlloc(alignment, size); }
    @Override public long nmemRealloc(long ptr, long size) { return MemoryUtil.nmemRealloc(ptr, size); }
    @Override public void nmemFree(long ptr) { MemoryUtil.nmemFree(ptr); }
    @Override public void nmemAlignedFree(long ptr) { MemoryUtil.nmemAlignedFree(ptr); }

    @Override public ByteBuffer memAlloc(int size) { return MemoryUtil.memAlloc(size); }
    @Override public ByteBuffer memCalloc(int size) { return MemoryUtil.memCalloc(size); }
    @Override public ByteBuffer memRealloc(ByteBuffer buffer, int size) { return MemoryUtil.memRealloc(buffer, size); }
    @Override public void memFree(Buffer buffer) { MemoryUtil.memFree(buffer); }
    @Override public ByteBuffer memByteBuffer(long address, int capacity) { return MemoryUtil.memByteBuffer(address, capacity); }
    @Override public long memAddress(Buffer buffer) { return MemoryUtil.memAddress(buffer); }
    @Override public long memAddress(Buffer buffer, int position) { return MemoryUtil.memAddress((ByteBuffer) buffer, position); }

    @Override public void memSet(long address, int value, long bytes) { MemoryUtil.memSet(address, value, bytes); }
    @Override public void memCopy(long src, long dst, long bytes) { MemoryUtil.memCopy(src, dst, bytes); }

    @Override public void memPutByte(long address, byte value) { MemoryAccess.putByte(address, value); }
    @Override public void memPutShort(long address, short value) { MemoryAccess.putShort(address, value); }
    @Override public void memPutInt(long address, int value) { MemoryAccess.putInt(address, value); }
    @Override public void memPutFloat(long address, float value) { MemoryAccess.putFloat(address, value); }
    @Override public void memPutLong(long address, long value) { MemoryAccess.putLong(address, value); }
    @Override public void memPutAddress(long address, long value) { MemoryAccess.putAddress(address, value); }

    @Override public byte memGetByte(long address) { return MemoryAccess.getByte(address); }
    @Override public short memGetShort(long address) { return MemoryAccess.getShort(address); }
    @Override public int memGetInt(long address) { return MemoryAccess.getInt(address); }
    @Override public float memGetFloat(long address) { return MemoryAccess.getFloat(address); }
    @Override public long memGetLong(long address) { return MemoryAccess.getLong(address); }
    @Override public long memGetAddress(long address) { return MemoryAccess.getAddress(address); }
    @Override public ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity) { return memByteBuffer(memAddress(buffer) + offset, capacity); }
}