package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.clientStateToVertexFlag;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ffpClientArrayPointer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isLegalVertexAttrib;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.texCoordConventional;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.warnOnce;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMVertexInput {

    private GLSMVertexInput() {}

    public static void glVertexPointer(int size, int stride, IntBuffer pointer) {
        GLSMVertexInput.glVertexPointer(size, GL11.GL_INT, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexPointer(int size, int stride, ShortBuffer pointer) {
        GLSMVertexInput.glVertexPointer(size, GL11.GL_SHORT, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_POSITION, size, GL11.GL_FLOAT, false, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexPointer(int size, int stride, DoubleBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_POSITION, size, GL11.GL_DOUBLE, false, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexPointer(int size, int type, int stride, ByteBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_POSITION, size, type, false, stride, pointer);
    }

    public static void glVertexPointer(int size, int type, int stride, long pointer_buffer_offset) {
        ffpClientArrayPointer(VAOManager.CONV_POSITION, size, type, false, stride, pointer_buffer_offset);
    }

    public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer pointer) {
        ffpClientArrayPointer(texCoordConventional(), size, type, false, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) {
        GLSMVertexInput.glTexCoordPointer(size, GL11.GL_FLOAT, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, IntBuffer pointer) {
        GLSMVertexInput.glTexCoordPointer(size, GL11.GL_INT, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) {
        GLSMVertexInput.glTexCoordPointer(size, GL11.GL_SHORT, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, DoubleBuffer pointer) {
        GLSMVertexInput.glTexCoordPointer(size, GL11.GL_DOUBLE, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int type, int stride, FloatBuffer pointer) {
        ffpClientArrayPointer(texCoordConventional(), size, GL11.GL_FLOAT, false, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glTexCoordPointer(int size, int type, int stride, ShortBuffer pointer) {
        ffpClientArrayPointer(texCoordConventional(), size, GL11.GL_SHORT, false, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glTexCoordPointer(int size, int type, int stride, IntBuffer pointer) {
        ffpClientArrayPointer(texCoordConventional(), size, GL11.GL_INT, false, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glTexCoordPointer(int size, int type, int stride, DoubleBuffer pointer) {
        ffpClientArrayPointer(texCoordConventional(), size, GL11.GL_DOUBLE, false, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glTexCoordPointer(int size, int type, int stride, long pointer_buffer_offset) {
        ffpClientArrayPointer(texCoordConventional(), size, type, false, stride, pointer_buffer_offset);
    }

    public static void glEdgeFlagPointer(int stride, ByteBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_EDGE_FLAG, 1, GL11.GL_UNSIGNED_BYTE, false, stride, pointer);
    }

    public static void glEdgeFlagPointer(int stride, long pointer_buffer_offset) {
        ffpClientArrayPointer(VAOManager.CONV_EDGE_FLAG, 1, GL11.GL_UNSIGNED_BYTE, false, stride, pointer_buffer_offset);
    }

    public static void glFogCoordPointer(int type, FloatBuffer data) {
        ffpClientArrayPointer(VAOManager.CONV_FOG_COORD, 1, GL11.GL_FLOAT, false, 0, MemoryUtilities.memByteBuffer(data));
    }

    public static void glFogCoordPointer(int type, DoubleBuffer data) {
        ffpClientArrayPointer(VAOManager.CONV_FOG_COORD, 1, GL11.GL_DOUBLE, false, 0, MemoryUtilities.memByteBuffer(data));
    }

    public static void glFogCoordPointer(int type, int stride, long data_buffer_offset) {
        ffpClientArrayPointer(VAOManager.CONV_FOG_COORD, 1, type, false, stride, data_buffer_offset);
    }

    public static void glSecondaryColorPointer(int size, int stride, FloatBuffer data) {
        ffpClientArrayPointer(VAOManager.CONV_SECONDARY_COLOR, size, GL11.GL_FLOAT, true, stride, MemoryUtilities.memByteBuffer(data));
    }

    public static void glSecondaryColorPointer(int size, int stride, DoubleBuffer data) {
        ffpClientArrayPointer(VAOManager.CONV_SECONDARY_COLOR, size, GL11.GL_DOUBLE, true, stride, MemoryUtilities.memByteBuffer(data));
    }

    public static void glSecondaryColorPointer(int size, boolean unsigned, int stride, ByteBuffer data) {
        final int type = unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE;
        ffpClientArrayPointer(VAOManager.CONV_SECONDARY_COLOR, size, type, true, stride, data);
    }

    public static void glSecondaryColorPointer(int size, int type, int stride, long data_buffer_offset) {
        ffpClientArrayPointer(VAOManager.CONV_SECONDARY_COLOR, size, type, true, stride, data_buffer_offset);
    }

    public static ByteBuffer glGetPointer(int pname, long result_size) {
        final int key = VAOManager.clientArrayField(pname, ctx().clientActiveTextureUnit);
        if ((key & ~0xFF) != VAOManager.FIELD_POINTER) return null;
        final VAOManager.Attrib a = ctx().vaos.conventional(key & 0xFF);
        if (a == null) return null;
        final long address = a.clientPointer != null ? MemoryUtilities.memAddress(a.clientPointer) : a.offset;
        return address == 0 ? null : MemoryUtilities.memByteBuffer(address, (int) result_size);
    }

    public static void glEnableClientState(int cap) {
        final int conv = VAOManager.pnameToConventional(cap, ctx().clientActiveTextureUnit);
        if (conv < 0) {
            warnOnce("client-state-" + cap, "glEnableClientState(0x{}) is not a client array GLSM tracks; ignoring", Integer.toHexString(cap));
            return;
        }
        ctx().vaos.applyConventionalEnabled(conv, true);
        final int flag = clientStateToVertexFlag(cap);
        if (flag != 0) ShaderManager.getInstance().enableClientVertexFlag(flag);
    }

    public static void glDisableClientState(int cap) {
        final int conv = VAOManager.pnameToConventional(cap, ctx().clientActiveTextureUnit);
        if (conv < 0) {
            warnOnce("client-state-" + cap, "glDisableClientState(0x{}) is not a client array GLSM tracks; ignoring", Integer.toHexString(cap));
            return;
        }
        ctx().vaos.applyConventionalEnabled(conv, false);
        final int flag = clientStateToVertexFlag(cap);
        if (flag != 0) ShaderManager.getInstance().disableClientVertexFlag(flag);
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long offset) {
        if (!isLegalVertexAttrib(index, size, type, stride, false)) return;
        if (ctx().vaos.setAttribute(index, size, type, normalized, stride, offset, ctx().boundVBO)) {
            RENDER_BACKEND.vertexAttribPointer(index, size, type, normalized, stride, offset);
        }
    }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, FloatBuffer pointer) {
        GLSMVertexInput.glVertexAttribPointer(index, size, GL11.GL_FLOAT, normalized, stride, pointer);
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, FloatBuffer pointer) {
        GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, DoubleBuffer pointer) {
        GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, IntBuffer pointer) {
        GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ShortBuffer pointer) {
        GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer) {
        if (!isLegalVertexAttrib(index, size, type, stride, false)) return;
        ctx().vaos.setAttribute(index, size, type, normalized, stride, pointer, 0);
    }

    public static void glEnableVertexAttribArray(int index) {
        if (ctx().vaos.enableAttribute(index)) ctx().vaos.repointAttrib(index);
        RENDER_BACKEND.enableVertexAttribArray(index);
    }

    public static void glDisableVertexAttribArray(int index) {
        if (ctx().vaos.disableAttribute(index)) ctx().vaos.repointAttrib(index);
        if (!ctx().vaos.isAttribEnabled(index)) RENDER_BACKEND.disableVertexAttribArray(index);
    }

    public static void glBindVertexArray(int array) {
        if (DisplayListManager.isRecording()) {
            DisplayListManager.recordBindVAO(array);
            // Since the vao needs to be bound to do stuff like state setup & data upload, it'll still execute the bind call.
            // This is technically wrong, but I'm not sure if there's a better solution here.
        }
        final GLContextState glCtx = ctx();
        if (array == 0) {
            array = glCtx.defaultVAO;
        }
        RENDER_BACKEND.bindVertexArray(array);
        if (glCtx.boundVAO != array) {
            glCtx.boundVAO = array;
            glCtx.vaos.onBindVertexArrayPre(array);
        }
    }

    public static void glDeleteVertexArrays(int array) {
        final GLContextState glCtx = ctx();
        glCtx.vaos.onDeleteVertexArray(array);
        if (array == glCtx.boundVAO) {
            glCtx.boundVAO = glCtx.defaultVAO;
            glCtx.vaos.onBindVertexArrayPre(glCtx.defaultVAO);
            RENDER_BACKEND.bindVertexArray(glCtx.defaultVAO);
        }
        RENDER_BACKEND.deleteVertexArrays(array);
    }

    public static void glDeleteVertexArrays(IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++) GLSMVertexInput.glDeleteVertexArrays(arrays.get(i));
    }

    public static void glDeleteVertexArrays(int[] arrays) {
        for (int array : arrays) GLSMVertexInput.glDeleteVertexArrays(array);
    }

    public static int glGenVertexArrays() {
        final int array = RENDER_BACKEND.genVertexArrays();
        ctx().vaos.onDeleteVertexArray(array);
        return array;
    }

    public static void glGenVertexArrays(IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++) arrays.put(i, GLSMVertexInput.glGenVertexArrays());
    }

    public static void glGenVertexArrays(int[] arrays) {
        for (int i = 0; i < arrays.length; i++) arrays[i] = GLSMVertexInput.glGenVertexArrays();
    }

    public static void glTexCoordPointer(int size, int type, int stride, int pointer) {
        GLSMVertexInput.glTexCoordPointer(size, type, stride, (long) pointer);
    }

    public static void glVertexPointer(int size, int type, int stride, int pointer) {
        GLSMVertexInput.glVertexPointer(size, type, stride, (long) pointer);
    }

    public static int getBoundEBO() {
            return ctx().vaos.boundEBO;
        }

    public static void glColorPointer(int size, int stride, FloatBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_COLOR, size, GL11.GL_FLOAT, Usage.COLOR.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glColorPointer(int size, int stride, DoubleBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_COLOR, size, GL11.GL_DOUBLE, Usage.COLOR.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glColorPointer(int size, int type, int stride, ByteBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_COLOR, size, type, Usage.COLOR.isNormalized(), stride, pointer);
        }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
            final int type = unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE;
            ffpClientArrayPointer(VAOManager.CONV_COLOR, size, type, Usage.COLOR.isNormalized(), stride, pointer);
        }

    public static void glColorPointer(int size, int type, int stride, long pointer_buffer_offset) {
            ffpClientArrayPointer(VAOManager.CONV_COLOR, size, type, Usage.COLOR.isNormalized(), stride, pointer_buffer_offset);
        }

    public static void glColorPointer(int size, int type, int stride, int pointer) {
            glColorPointer(size, type, stride, (long) pointer);
        }

    public static void glNormalPointer(int stride, FloatBuffer pointer) {
            glNormalPointer(GL11.GL_FLOAT, stride, pointer);
        }

    public static void glNormalPointer(int stride, ByteBuffer pointer) {
            glNormalPointer(GL11.GL_BYTE, stride, pointer);
        }

    public static void glNormalPointer(int stride, IntBuffer pointer) {
            glNormalPointer(GL11.GL_INT, stride, pointer);
        }

    public static void glNormalPointer(int stride, DoubleBuffer pointer) {
            glNormalPointer(GL11.GL_DOUBLE, stride, pointer);
        }

    public static void glNormalPointer(int type, int stride, ByteBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, type, Usage.NORMAL.isNormalized(), stride, pointer);
        }

    public static void glNormalPointer(int type, int stride, FloatBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_FLOAT, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glNormalPointer(int type, int stride, ShortBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_SHORT, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glNormalPointer(int type, int stride, IntBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_INT, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glNormalPointer(int type, int stride, DoubleBuffer pointer) {
            ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_DOUBLE, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glNormalPointer(int type, int stride, long pointer_buffer_offset) {
            ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, type, Usage.NORMAL.isNormalized(), stride, pointer_buffer_offset);
        }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long offset) {
            if (!isLegalVertexAttrib(index, size, type, stride, true)) return;
            if (ctx().vaos.setIntegerAttribute(index, size, type, stride, offset, ctx().boundVBO)) {
                RENDER_BACKEND.vertexAttribIPointer(index, size, type, stride, offset);
            }
        }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, ByteBuffer pointer) {
            if (!isLegalVertexAttrib(index, size, type, stride, true)) return;
            ctx().vaos.setIntegerAttribute(index, size, type, stride, pointer, 0);
        }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, IntBuffer pointer) {
            glVertexAttribIPointer(index, size, type, stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, ShortBuffer pointer) {
            glVertexAttribIPointer(index, size, type, stride, MemoryUtilities.memByteBuffer(pointer));
        }

    public static void glVertexAttribDivisor(int index, int divisor) {
            if (ctx().vaos.setDivisor(index, divisor)) RENDER_BACKEND.vertexAttribDivisor(index, divisor);
        }

    public static void glInterleavedArrays(int format, int stride, long pointer) {
            if (!ShaderManager.getInstance().isActive()) {
                return;
            }

            // Mesa _mesa_get_interleaved_layout decomposition
            final int f = 4; // sizeof(float)
            final int c = f * ((4 + (f - 1)) / f); // 4 ubytes padded to float alignment = 4

            boolean tflag = false, cflag = false, nflag = false;
            int tcomps = 0, ccomps = 0, vcomps = 0;
            int ctype = 0;
            final int toffset = 0;
            int coffset = 0;
            int noffset = 0;
            int voffset = 0;
            final int defstride;

            switch (format) {
                case GL11.GL_V2F:
                    vcomps = 2; defstride = 2 * f; break;
                case GL11.GL_V3F:
                    vcomps = 3; defstride = 3 * f; break;
                case GL11.GL_C4UB_V2F:
                    cflag = true; ccomps = 4; vcomps = 2;
                    ctype = GL11.GL_UNSIGNED_BYTE; voffset = c; defstride = c + 2 * f; break;
                case GL11.GL_C4UB_V3F:
                    cflag = true; ccomps = 4; vcomps = 3;
                    ctype = GL11.GL_UNSIGNED_BYTE; voffset = c; defstride = c + 3 * f; break;
                case GL11.GL_C3F_V3F:
                    cflag = true; ccomps = 3; vcomps = 3;
                    ctype = GL11.GL_FLOAT; voffset = 3 * f; defstride = 6 * f; break;
                case GL11.GL_N3F_V3F:
                    nflag = true; vcomps = 3;
                    voffset = 3 * f; defstride = 6 * f; break;
                case GL11.GL_C4F_N3F_V3F:
                    cflag = true; nflag = true; ccomps = 4; vcomps = 3;
                    ctype = GL11.GL_FLOAT; noffset = 4 * f; voffset = 7 * f; defstride = 10 * f; break;
                case GL11.GL_T2F_V3F:
                    tflag = true; tcomps = 2; vcomps = 3;
                    voffset = 2 * f; defstride = 5 * f; break;
                case GL11.GL_T4F_V4F:
                    tflag = true; tcomps = 4; vcomps = 4;
                    voffset = 4 * f; defstride = 8 * f; break;
                case GL11.GL_T2F_C4UB_V3F:
                    tflag = true; cflag = true; tcomps = 2; ccomps = 4; vcomps = 3;
                    ctype = GL11.GL_UNSIGNED_BYTE; coffset = 2 * f; voffset = c + 2 * f; defstride = c + 5 * f; break;
                case GL11.GL_T2F_C3F_V3F:
                    tflag = true; cflag = true; tcomps = 2; ccomps = 3; vcomps = 3;
                    ctype = GL11.GL_FLOAT; coffset = 2 * f; voffset = 5 * f; defstride = 8 * f; break;
                case GL11.GL_T2F_N3F_V3F:
                    tflag = true; nflag = true; tcomps = 2; vcomps = 3;
                    noffset = 2 * f; voffset = 5 * f; defstride = 8 * f; break;
                case GL11.GL_T2F_C4F_N3F_V3F:
                    tflag = true; cflag = true; nflag = true; tcomps = 2; ccomps = 4; vcomps = 3;
                    ctype = GL11.GL_FLOAT; coffset = 2 * f; noffset = 6 * f; voffset = 9 * f; defstride = 12 * f; break;
                case GL11.GL_T4F_C4F_N3F_V4F:
                    tflag = true; cflag = true; nflag = true; tcomps = 4; ccomps = 4; vcomps = 4;
                    ctype = GL11.GL_FLOAT; coffset = 4 * f; noffset = 8 * f; voffset = 11 * f; defstride = 15 * f; break;
                default:
                    return; // Invalid format
            }

            if (stride == 0) stride = defstride;

            if (tflag) {
                glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                glTexCoordPointer(tcomps, GL11.GL_FLOAT, stride, pointer + toffset);
            } else {
                glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            }

            if (cflag) {
                glEnableClientState(GL11.GL_COLOR_ARRAY);
                glColorPointer(ccomps, ctype, stride, pointer + coffset);
            } else {
                glDisableClientState(GL11.GL_COLOR_ARRAY);
            }

            if (nflag) {
                glEnableClientState(GL11.GL_NORMAL_ARRAY);
                glNormalPointer(GL11.GL_FLOAT, stride, pointer + noffset);
            } else {
                glDisableClientState(GL11.GL_NORMAL_ARRAY);
            }

            glEnableClientState(GL11.GL_VERTEX_ARRAY);
            glVertexPointer(vcomps, GL11.GL_FLOAT, stride, pointer + voffset);
        }

    public static void glInterleavedArrays(int format, int stride, ByteBuffer pointer) {
            glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(pointer));
        }

    public static void glInterleavedArrays(int format, int stride, FloatBuffer pointer) {
            glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
        }

    public static void glInterleavedArrays(int format, int stride, DoubleBuffer pointer) {
            glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
        }

    public static void glInterleavedArrays(int format, int stride, IntBuffer pointer) {
            glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
        }

    public static void glInterleavedArrays(int format, int stride, ShortBuffer pointer) {
            glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
        }

    public static boolean glIsVertexArray(int array) {
            return RENDER_BACKEND.isVertexArray(array);
        }

    public static void glBindVertexArrayAPPLE(int array) {
            glBindVertexArray(array);
        }

    public static int getBoundVAO() {
            return ctx().boundVAO;
        }
}
