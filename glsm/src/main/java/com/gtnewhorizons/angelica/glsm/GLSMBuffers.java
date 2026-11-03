package com.gtnewhorizons.angelica.glsm;

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
        final ByteBuffer copy = copyOf(data);
        GLSMBuffers.glBufferData(target, copy, usage);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferData(int target, long[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
        GLSMBuffers.glBufferData(target, copy, usage);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferData(int target, double[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
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
        final ByteBuffer copy = copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, int[] data) {
        final ByteBuffer copy = copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, long[] data) {
        final ByteBuffer copy = copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, float[] data) {
        final ByteBuffer copy = copyOf(data);
        GLSMBuffers.glBufferSubData(target, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void glBufferSubData(int target, long offset, double[] data) {
        final ByteBuffer copy = copyOf(data);
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

}
