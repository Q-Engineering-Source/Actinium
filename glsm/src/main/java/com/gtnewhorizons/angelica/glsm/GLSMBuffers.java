package com.gtnewhorizons.angelica.glsm;

import java.nio.Buffer;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL31;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.copyOf;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.invalidateDeletedBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.rejectExitCapture;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMBuffers {

    private GLSMBuffers() {}

    public static void glDeleteBuffers(int buffer) {
        rejectExitCapture();
        if (buffer == 0) return;
        invalidateDeletedBuffer(buffer);
        RENDER_BACKEND.deleteBuffers(buffer);
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
        rejectExitCapture();
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            invalidateDeletedBuffer(buffers.get(i));
        }
        RENDER_BACKEND.deleteBuffers(buffers);
    }

    public static void glDeleteBuffers(int[] buffers) {
        for (int buffer : buffers) {
            GLSMBuffers.glDeleteBuffers(buffer);
        }
    }

    public static void glBindBuffer(int target, int buffer) {
        rejectExitCapture();
        if (target == GL15.GL_ARRAY_BUFFER) {
            ctx().boundVBO = buffer;
        } else if (target == GL15.GL_ELEMENT_ARRAY_BUFFER) {
            ctx().vaos.onBindEBO(buffer);
        } else if (target == GL21.GL_PIXEL_UNPACK_BUFFER) {
            ctx().boundPixelUnpackBuffer = buffer;
        } else if (target == GL21.GL_PIXEL_PACK_BUFFER) {
            ctx().boundPixelPackBuffer = buffer;
        } else if (target == GL31.GL_COPY_READ_BUFFER) {
            ctx().boundCopyReadBuffer = buffer;
        } else if (target == GL31.GL_COPY_WRITE_BUFFER) {
            ctx().boundCopyWriteBuffer = buffer;
        }
        RENDER_BACKEND.bindBuffer(target, buffer);
    }

    public static int glGenBuffers() {
        return RENDER_BACKEND.genBuffers();
    }

    public static void glGenBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) buffers.put(i, RENDER_BACKEND.genBuffers());
    }

    public static void glGenBuffers(int[] buffers) {
        for (int i = 0; i < buffers.length; i++) buffers[i] = RENDER_BACKEND.genBuffers();
    }

    public static void glBufferData(int target, long size, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, size, usage); }

    public static void glBufferData(int target, int[] data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, float[] data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.ByteBuffer data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.ShortBuffer data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.IntBuffer data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.FloatBuffer data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.DoubleBuffer data, int usage) { onBufferRespec(target); RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, LongBuffer data, int usage) { onBufferRespec(target); glBufferData(target, MemoryUtilities.memByteBuffer(data), usage); }

    public static void glBufferData(int target, short[] data, int usage) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferData(target, copy, usage);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferData(int target, long[] data, int usage) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferData(target, copy, usage);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferData(int target, double[] data, int usage) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferData(target, copy, usage);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, java.nio.ByteBuffer data) { onBufferRespec(target); RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.ShortBuffer data) { onBufferRespec(target); RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.IntBuffer data) { onBufferRespec(target); RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.FloatBuffer data) { onBufferRespec(target); RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.DoubleBuffer data) { onBufferRespec(target); RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, LongBuffer data) { glBufferSubData(target, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glBufferSubData(int target, long offset, short[] data) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, int[] data) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, long[] data) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, float[] data) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, double[] data) {
        final ByteBuffer copy = GLStateManager.copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glGetBufferSubData(int target, long offset, java.nio.ByteBuffer data) { rejectExitCapture(); RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.ShortBuffer data) { rejectExitCapture(); RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.IntBuffer data) { rejectExitCapture(); RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.FloatBuffer data) { rejectExitCapture(); RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.DoubleBuffer data) { rejectExitCapture(); RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, LongBuffer data) { glGetBufferSubData(target, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glGetBufferSubData(int target, long offset, short[] data) {
        final ByteBuffer copy = MemoryUtilities.memAlloc(data.length << 1);
        GLSMBuffers.glGetBufferSubData(target, offset, copy);
        copy.asShortBuffer().get(data);
        MemoryUtilities.memFree(copy);
    }

    public static void glGetBufferSubData(int target, long offset, int[] data) {
        final ByteBuffer copy = MemoryUtilities.memAlloc(data.length << 2);
        GLSMBuffers.glGetBufferSubData(target, offset, copy);
        copy.asIntBuffer().get(data);
        MemoryUtilities.memFree(copy);
    }

    public static void glGetBufferSubData(int target, long offset, long[] data) {
        final ByteBuffer copy = MemoryUtilities.memAlloc(data.length << 3);
        GLSMBuffers.glGetBufferSubData(target, offset, copy);
        copy.asLongBuffer().get(data);
        MemoryUtilities.memFree(copy);
    }

    public static void glGetBufferSubData(int target, long offset, float[] data) {
        final ByteBuffer copy = MemoryUtilities.memAlloc(data.length << 2);
        GLSMBuffers.glGetBufferSubData(target, offset, copy);
        copy.asFloatBuffer().get(data);
        MemoryUtilities.memFree(copy);
    }

    public static void glGetBufferSubData(int target, long offset, double[] data) {
        final ByteBuffer copy = MemoryUtilities.memAlloc(data.length << 3);
        GLSMBuffers.glGetBufferSubData(target, offset, copy);
        copy.asDoubleBuffer().get(data);
        MemoryUtilities.memFree(copy);
    }

    public static int glGetBufferParameteri(int target, int pname) { rejectExitCapture(); return RENDER_BACKEND.getBufferParameteri(target, pname); }

    public static void glDeleteSync(long sync) {
        rejectExitCapture();
        RENDER_BACKEND.deleteSync(sync);
    }

    public static long glFenceSync(int condition, int flags) { rejectExitCapture(); return RENDER_BACKEND.fenceSync(condition, flags); }

    public static int glClientWaitSync(long sync, int flags, long timeout) { rejectExitCapture(); return RENDER_BACKEND.clientWaitSync(sync, flags, timeout); }

    public static void glWaitSync(long sync, int flags, long timeout) { rejectExitCapture(); RENDER_BACKEND.waitSync(sync, flags, timeout); }

    public static int glGetSynci(long sync, int pname, IntBuffer length) { rejectExitCapture(); return RENDER_BACKEND.getSynci(sync, pname, length); }

    public static int glCreateBuffers() {
            return RENDER_BACKEND.createBuffers();
        }

    public static void glCreateBuffers(IntBuffer buffers) {
            for (int i = buffers.position(); i < buffers.limit(); i++) buffers.put(i, RENDER_BACKEND.createBuffers());
        }

    public static void glCreateBuffers(int[] buffers) {
            for (int i = 0; i < buffers.length; i++) buffers[i] = RENDER_BACKEND.createBuffers();
        }

    public static void nglBufferData(int target, long size, long data, int usage) {
            if (data == 0L) {
                glBufferData(target, size, usage);
                return;
            }
            glBufferData(target, MemoryUtilities.memByteBuffer(data, checkedSize(size)), usage);
        }

    private static int bufferForTarget(GLContextState glCtx, int target) {
        if (target == GL15.GL_ARRAY_BUFFER) return glCtx.boundVBO;
        if (target == GL15.GL_ELEMENT_ARRAY_BUFFER) return glCtx.vaos.boundEBO;
        if (target == GL31.GL_COPY_READ_BUFFER) return glCtx.boundCopyReadBuffer;
        if (target == GL31.GL_COPY_WRITE_BUFFER) return glCtx.boundCopyWriteBuffer;
        if (target == GL21.GL_PIXEL_UNPACK_BUFFER) return glCtx.boundPixelUnpackBuffer;
        if (target == GL21.GL_PIXEL_PACK_BUFFER) return glCtx.boundPixelPackBuffer;
        return 0;
    }

    private static void onBufferRespec(int target) {
        rejectExitCapture();
        final GLContextState glCtx = ctx();
        final int buffer = bufferForTarget(glCtx, target);
        if (buffer != 0) onNamedBufferRespec(buffer);
    }

    private static void onNamedBufferRespec(int buffer) {
        rejectExitCapture();
    }

    private static void onBufferStorage(int target, int flags) {
        onBufferRespec(target);
    }

    static int checkedSize(long size) {            if (size < 0 || size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Buffer range does not fit a ByteBuffer wrapper: " + size);
            }
            return (int) size;
        }

    public static ByteBuffer glMapBuffer(int target, int access) {
        rejectExitCapture();
        return RENDER_BACKEND.mapBuffer(target, access);
    }

    public static ByteBuffer glMapBuffer(int target, int access, ByteBuffer old_buffer) { return glMapBuffer(target, access); }

    public static ByteBuffer glMapBuffer(int target, int access, long length, ByteBuffer old_buffer) {
        rejectExitCapture();
        return RENDER_BACKEND.mapBuffer(target, access, length, old_buffer);
    }

    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access) {
        rejectExitCapture();
        return RENDER_BACKEND.mapBufferRange(target, offset, length, access);
    }

    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer old_buffer) { return glMapBufferRange(target, offset, length, access); }

    public static long glMapBufferRangeAddress(int target, long offset, long length, int access) {
        rejectExitCapture();
        return RENDER_BACKEND.mapBufferRangeAddress(target, offset, length, access);
    }

    public static void glFlushMappedBufferRange(int target, long offset, long length) { rejectExitCapture(); RENDER_BACKEND.flushMappedBufferRange(target, offset, length); }

    public static boolean glUnmapBuffer(int target) {
        rejectExitCapture();
        return RENDER_BACKEND.unmapBuffer(target);
    }

    public static int glGetBufferParameter(int target, int pname) { return glGetBufferParameteri(target, pname); }

    public static void glGetBufferParameter(int target, int pname, IntBuffer params) { params.put(params.position(), glGetBufferParameteri(target, pname)); }

    public static void glGetBufferParameteriv(int target, int pname, IntBuffer params) { params.put(params.position(), glGetBufferParameteri(target, pname)); }

    public static void glGetBufferParameteriv(int target, int pname, int[] params) { params[0] = glGetBufferParameteri(target, pname); }

    public static void glBufferStorage(int target, java.nio.ByteBuffer data, int flags) { onBufferStorage(target, flags); RENDER_BACKEND.bufferStorage(target, data, flags); }

    public static void glBufferStorage(int target, long size, int flags) { onBufferStorage(target, flags); RENDER_BACKEND.bufferStorage(target, size, flags); }

    public static void glBufferStorage(int target, ShortBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }

    public static void glBufferStorage(int target, IntBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }

    public static void glBufferStorage(int target, FloatBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }

    public static void glBufferStorage(int target, DoubleBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }

    public static void glBufferStorage(int target, short[] data, int flags) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glBufferStorage(target, copy, flags);
            MemoryUtilities.memFree(copy);
        }

    public static void glBufferStorage(int target, int[] data, int flags) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glBufferStorage(target, copy, flags);
            MemoryUtilities.memFree(copy);
        }

    public static void glBufferStorage(int target, float[] data, int flags) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glBufferStorage(target, copy, flags);
            MemoryUtilities.memFree(copy);
        }

    public static void glBufferStorage(int target, double[] data, int flags) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glBufferStorage(target, copy, flags);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferData(int buffer, long size, int usage) { onNamedBufferRespec(buffer); RENDER_BACKEND.namedBufferData(buffer, size, usage); }

    public static void glNamedBufferData(int buffer, ByteBuffer data, int usage) { onNamedBufferRespec(buffer); RENDER_BACKEND.namedBufferData(buffer, data, usage); }

    public static void glNamedBufferData(int buffer, FloatBuffer data, int usage) { onNamedBufferRespec(buffer); RENDER_BACKEND.namedBufferData(buffer, data, usage); }

    public static void glNamedBufferData(int buffer, ShortBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }

    public static void glNamedBufferData(int buffer, IntBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }

    public static void glNamedBufferData(int buffer, LongBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }

    public static void glNamedBufferData(int buffer, DoubleBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }

    public static void glNamedBufferData(int buffer, short[] data, int usage) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferData(buffer, copy, usage);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferData(int buffer, int[] data, int usage) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferData(buffer, copy, usage);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferData(int buffer, long[] data, int usage) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferData(buffer, copy, usage);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferData(int buffer, float[] data, int usage) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferData(buffer, copy, usage);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferData(int buffer, double[] data, int usage) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferData(buffer, copy, usage);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { onNamedBufferRespec(buffer); RENDER_BACKEND.namedBufferSubData(buffer, offset, data); }

    public static void glNamedBufferSubData(int buffer, long offset, ShortBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glNamedBufferSubData(int buffer, long offset, IntBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glNamedBufferSubData(int buffer, long offset, LongBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glNamedBufferSubData(int buffer, long offset, FloatBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glNamedBufferSubData(int buffer, long offset, DoubleBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }

    public static void glNamedBufferSubData(int buffer, long offset, short[] data) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferSubData(buffer, offset, copy);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferSubData(int buffer, long offset, int[] data) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferSubData(buffer, offset, copy);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferSubData(int buffer, long offset, long[] data) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferSubData(buffer, offset, copy);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferSubData(int buffer, long offset, float[] data) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferSubData(buffer, offset, copy);
            MemoryUtilities.memFree(copy);
        }

    public static void glNamedBufferSubData(int buffer, long offset, double[] data) {
            final ByteBuffer copy = GLStateManager.copyOf(data);
            glNamedBufferSubData(buffer, offset, copy);
            MemoryUtilities.memFree(copy);
        }

    public static void glBindBufferBase(int target, int index, int buffer) {
        rejectExitCapture();
        RENDER_BACKEND.bindBufferBase(target, index, buffer);
    }

    public static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        rejectExitCapture();
        RENDER_BACKEND.bindBufferRange(target, index, buffer, offset, size);
    }

    public static int getIndexedBufferBinding(int target, int index) {
        return RENDER_BACKEND.getIndexedBufferBinding(target, index);
    }

    public static void glClearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) {
        onBufferRespec(target);
        RENDER_BACKEND.clearBufferData(target, internalformat, format, type, data);
    }

    public static void glClearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data) {
        onBufferRespec(target);
        RENDER_BACKEND.clearBufferSubData(target, internalFormat, offset, size, format, type, data);
    }

    public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        onBufferRespec(writeTarget);
        RENDER_BACKEND.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
    }

    public static boolean glIsBuffer(int buffer) { rejectExitCapture(); return RENDER_BACKEND.isBuffer(buffer); }

    public static void glMemoryBarrier(int barriers) { rejectExitCapture(); RENDER_BACKEND.memoryBarrier(barriers); }

    public static void nglBufferSubData(int target, long offset, long size, long data) { glBufferSubData(target, offset, MemoryUtilities.memByteBuffer(data, checkedSize(size))); }

    public static void nglGetBufferSubData(int target, long offset, long size, long data) { glGetBufferSubData(target, offset, MemoryUtilities.memByteBuffer(data, checkedSize(size))); }

    public static long nglMapBuffer(int target, int access) {
        rejectExitCapture();
            final ByteBuffer buf = RENDER_BACKEND.mapBuffer(target, access);
            return buf == null ? 0L : MemoryUtilities.memAddress0(buf);
        }

    public static int getBoundVBO() {
            return ctx().boundVBO;
        }

    public static int getBoundCopyReadBuffer() {
        return ctx().boundCopyReadBuffer;
    }

    public static int getBoundCopyWriteBuffer() {
        return ctx().boundCopyWriteBuffer;
    }
}
