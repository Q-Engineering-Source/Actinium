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

import static com.gtnewhorizons.angelica.glsm.GLStateManager.copyOf;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.invalidateDeletedBuffer;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMBuffers {

    private GLSMBuffers() {}

    public static void glDeleteBuffers(int buffer) {
        if (buffer == 0) return;
        invalidateDeletedBuffer(buffer);
        RENDER_BACKEND.deleteBuffers(buffer);
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
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
        if (target == GL15.GL_ARRAY_BUFFER) {
            ctx().boundVBO = buffer;
        } else if (target == GL15.GL_ELEMENT_ARRAY_BUFFER) {
            ctx().vaos.onBindEBO(buffer);
        } else if (target == GL21.GL_PIXEL_UNPACK_BUFFER) {
            ctx().boundPixelUnpackBuffer = buffer;
        } else if (target == GL21.GL_PIXEL_PACK_BUFFER) {
            ctx().boundPixelPackBuffer = buffer;
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

    public static void glBufferData(int target, long size, int usage) { RENDER_BACKEND.bufferData(target, size, usage); }

    public static void glBufferData(int target, int[] data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, float[] data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.ByteBuffer data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.ShortBuffer data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.IntBuffer data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.FloatBuffer data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, java.nio.DoubleBuffer data, int usage) { RENDER_BACKEND.bufferData(target, data, usage); }

    public static void glBufferData(int target, LongBuffer data, int usage) { glBufferData(target, MemoryUtilities.memByteBuffer(data), usage); }

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

    public static void glBufferSubData(int target, long offset, java.nio.ByteBuffer data) { RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.ShortBuffer data) { RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.IntBuffer data) { RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.FloatBuffer data) { RENDER_BACKEND.bufferSubData(target, offset, data); }

    public static void glBufferSubData(int target, long offset, java.nio.DoubleBuffer data) { RENDER_BACKEND.bufferSubData(target, offset, data); }

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

    public static void glGetBufferSubData(int target, long offset, java.nio.ByteBuffer data) { RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.ShortBuffer data) { RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.IntBuffer data) { RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.FloatBuffer data) { RENDER_BACKEND.getBufferSubData(target, offset, data); }

    public static void glGetBufferSubData(int target, long offset, java.nio.DoubleBuffer data) { RENDER_BACKEND.getBufferSubData(target, offset, data); }

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

    public static int glGetBufferParameteri(int target, int pname) { return RENDER_BACKEND.getBufferParameteri(target, pname); }

    public static void glDeleteSync(long sync) {
            RENDER_BACKEND.deleteSync(sync);
        }

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

    static int checkedSize(long size) {
            if (size < 0 || size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Buffer range does not fit a ByteBuffer wrapper: " + size);
            }
            return (int) size;
        }

    public static ByteBuffer glMapBuffer(int target, int access) { return RENDER_BACKEND.mapBuffer(target, access); }

    public static ByteBuffer glMapBuffer(int target, int access, ByteBuffer old_buffer) { return glMapBuffer(target, access); }

    public static ByteBuffer glMapBuffer(int target, int access, long length, ByteBuffer old_buffer) { return RENDER_BACKEND.mapBuffer(target, access, length, old_buffer); }

    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access) { return RENDER_BACKEND.mapBufferRange(target, offset, length, access); }

    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer old_buffer) { return glMapBufferRange(target, offset, length, access); }

    public static boolean glUnmapBuffer(int target) { return RENDER_BACKEND.unmapBuffer(target); }

    public static void glGetBufferParameteriv(int target, int pname, IntBuffer params) { params.put(params.position(), glGetBufferParameteri(target, pname)); }

    public static void glGetBufferParameteriv(int target, int pname, int[] params) { params[0] = glGetBufferParameteri(target, pname); }

    public static void glBufferStorage(int target, java.nio.ByteBuffer data, int flags) { RENDER_BACKEND.bufferStorage(target, data, flags); }

    public static void glBufferStorage(int target, long size, int flags) { RENDER_BACKEND.bufferStorage(target, size, flags); }

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

    public static void glNamedBufferData(int buffer, long size, int usage) { RENDER_BACKEND.namedBufferData(buffer, size, usage); }

    public static void glNamedBufferData(int buffer, ByteBuffer data, int usage) { RENDER_BACKEND.namedBufferData(buffer, data, usage); }

    public static void glNamedBufferData(int buffer, FloatBuffer data, int usage) { RENDER_BACKEND.namedBufferData(buffer, data, usage); }

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

    public static void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { RENDER_BACKEND.namedBufferSubData(buffer, offset, data); }

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

    public static void glBindBufferBase(int target, int index, int buffer) { RENDER_BACKEND.bindBufferBase(target, index, buffer); }

    public static long nglMapBuffer(int target, int access) {
            final ByteBuffer buf = RENDER_BACKEND.mapBuffer(target, access);
            return buf == null ? 0L : MemoryUtilities.memAddress0(buf);
        }

    public static int getBoundVBO() {
            return ctx().boundVBO;
        }
}
