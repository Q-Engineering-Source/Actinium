package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandPhase;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gpuCheckpoint;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordGpuCommand;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.requireContext;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMFramebuffer {

    private GLSMFramebuffer() {}

    public static void glDrawBuffer(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffer(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) mod(ctx().drawBuffer).setValue(mode);
        RENDER_BACKEND.drawBuffer(mode);
    }

    public static void glClear(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClear(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        recordGpuCommand(GpuCommandType.CLEAR, GpuCommandPhase.BEGIN, mask, 0);
        RENDER_BACKEND.clear(mask);
        recordGpuCommand(GpuCommandType.CLEAR, GpuCommandPhase.END, mask, 0);
        gpuCheckpoint(GpuCommandType.CLEAR);
    }

    public static void glReadBuffer(int mode) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glReadBuffer in display lists not yet implemented - if you see this, please report!");
        }
        RENDER_BACKEND.readBuffer(mode);
    }

    public static void glDrawBuffers(int buffer) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffers(1, buffer);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.drawBuffers(buffer);
    }

    public static void glDrawBuffers(IntBuffer bufs) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffers(bufs.remaining(), bufs);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.drawBuffers(bufs);
    }

    public static void glDrawBuffers(int[] bufs) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffers(bufs.length, IntBuffer.wrap(bufs));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.drawBuffers(bufs);
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        if (target == GL30.GL_FRAMEBUFFER) {
            if (ctx().drawFramebuffer == framebuffer && ctx().readFramebuffer == framebuffer) return;
            if (ctx().drawFramebuffer != framebuffer) ctx().drawFramebufferGeneration++;
            ctx().drawFramebuffer = framebuffer;
            ctx().readFramebuffer = framebuffer;
        } else if (target == GL30.GL_DRAW_FRAMEBUFFER) {
            if (ctx().drawFramebuffer == framebuffer) return;
            ctx().drawFramebufferGeneration++;
            ctx().drawFramebuffer = framebuffer;
        } else if (target == GL30.GL_READ_FRAMEBUFFER) {
            if (ctx().readFramebuffer == framebuffer) return;
            ctx().readFramebuffer = framebuffer;
        }
        recordGpuCommand(GpuCommandType.FRAMEBUFFER_BIND, target, framebuffer);
        RENDER_BACKEND.bindFramebuffer(target, framebuffer);
    }

    public static void glDeleteFramebuffers(int framebuffer) {
        if (ctx().drawFramebuffer == framebuffer) ctx().drawFramebuffer = 0;
        if (ctx().readFramebuffer == framebuffer) ctx().readFramebuffer = 0;
        RENDER_BACKEND.deleteFramebuffers(framebuffer);
    }

    public static void glDeleteFramebuffers(IntBuffer framebuffers) {
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) glDeleteFramebuffers(framebuffers.get(i));
    }

    public static void glDeleteFramebuffers(int[] framebuffers) {
        for (int framebuffer : framebuffers) glDeleteFramebuffers(framebuffer);
    }

    public static int glGenFramebuffers() {
        return RENDER_BACKEND.genFramebuffers();
    }

    public static void glGenFramebuffers(IntBuffer framebuffers) {
        requireContext("glGenFramebuffers");
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) framebuffers.put(i, RENDER_BACKEND.genFramebuffers());
    }

    public static void glGenFramebuffers(int[] framebuffers) {
        requireContext("glGenFramebuffers");
        for (int i = 0; i < framebuffers.length; i++) framebuffers[i] = RENDER_BACKEND.genFramebuffers();
    }

    public static int glCheckFramebufferStatus(int target) {
        return RENDER_BACKEND.checkFramebufferStatus(target);
    }

    public static int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        return RENDER_BACKEND.getFramebufferAttachmentParameteri(target, attachment, pname);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        RENDER_BACKEND.framebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static int glGenRenderbuffers() { return RENDER_BACKEND.genRenderbuffers(); }

    public static void glGenRenderbuffers(IntBuffer renderbuffers) {
        requireContext("glGenRenderbuffers");
        for (int i = renderbuffers.position(); i < renderbuffers.limit(); i++) renderbuffers.put(i, RENDER_BACKEND.genRenderbuffers());
    }

    public static void glGenRenderbuffers(int[] renderbuffers) {
        requireContext("glGenRenderbuffers");
        for (int i = 0; i < renderbuffers.length; i++) renderbuffers[i] = RENDER_BACKEND.genRenderbuffers();
    }

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) { RENDER_BACKEND.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer); }
}
