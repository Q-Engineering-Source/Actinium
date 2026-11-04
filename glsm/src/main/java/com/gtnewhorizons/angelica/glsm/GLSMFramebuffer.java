package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.GpuCommandDiagnostics;
import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandPhase;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.rejectExitCapture;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gpuCheckpoint;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordGpuCommand;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.requireContext;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.initConfig;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isGpuDiagnosticCacheTrusted;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.remapPixelTypeForGLES;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.restorePixelPackBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.suspendPixelPackBuffer;

public final class GLSMFramebuffer {

    private GLSMFramebuffer() {}

    private static long firstClearBlockNanos;

    public static void glDrawBuffer(int mode) {
        rejectExitCapture();
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
        rejectExitCapture();
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

    public static long takeFirstClearBlockNanos() {
        final long value = firstClearBlockNanos;
        firstClearBlockNanos = 0L;
        return value;
    }

    public static void glReadBuffer(int mode) {
        rejectExitCapture();
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
        rejectExitCapture();
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
        rejectExitCapture();
        if (ctx().drawFramebuffer == framebuffer) ctx().drawFramebuffer = 0;
        if (ctx().readFramebuffer == framebuffer) ctx().readFramebuffer = 0;
        // Slots of a deleted framebuffer no longer keep their textures alive.
        final int[] slots = ctx().framebufferTextures.remove(framebuffer);
        if (slots != null) {
            for (int slot = 0; slot < slots.length; slot++) GLSMTextureLifetime.setFramebufferSlot(slots, slot, 0);
        }
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
        rejectExitCapture();
        return RENDER_BACKEND.checkFramebufferStatus(target);
    }

    public static int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        rejectExitCapture();
        return RENDER_BACKEND.getFramebufferAttachmentParameteri(target, attachment, pname);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        rejectExitCapture();
        if (!GLSMTextureLifetime.beginAttach(target, attachment, texture)) return;
        RENDER_BACKEND.framebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static void glFramebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        rejectExitCapture();
        if (!GLSMTextureLifetime.beginAttach(target, attachment, texture)) return;
        RENDER_BACKEND.framebufferTexture1D(target, attachment, textarget, texture, level);
    }

    public static void glFramebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {
        rejectExitCapture();
        if (!GLSMTextureLifetime.beginAttach(target, attachment, texture)) return;
        RENDER_BACKEND.framebufferTexture3D(target, attachment, textarget, texture, level, zoffset);
    }

    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        rejectExitCapture();
        if (!GLSMTextureLifetime.beginAttach(target, attachment, texture)) return;
        RENDER_BACKEND.framebufferTextureLayer(target, attachment, texture, level, layer);
    }

    public static void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        rejectExitCapture();
        if (!GLSMTextureLifetime.beginAttach(ctx(), framebuffer, attachment, texture)) return;
        RENDER_BACKEND.namedFramebufferTexture(framebuffer, attachment, texture, level);
    }

    public static void glNamedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) {
        RENDER_BACKEND.namedFramebufferTextureLayer(framebuffer, attachment, texture, level, layer);
    }

    public static void glFramebufferTextureFace(int target, int attachment, int texture, int level, int face) {
        glFramebufferTexture2D(target, attachment, face, texture, level);
    }

    public static void glNamedFramebufferTextureEXT(int framebuffer, int attachment, int texture, int level) {
        final int previous = swapDrawFramebuffer(framebuffer);
        glFramebufferTexture(GL30.GL_DRAW_FRAMEBUFFER, attachment, texture, level);
        swapDrawFramebuffer(previous);
    }

    public static void glNamedFramebufferTexture1DEXT(int framebuffer, int attachment, int textarget, int texture, int level) {
        final int previous = swapDrawFramebuffer(framebuffer);
        glFramebufferTexture1D(GL30.GL_DRAW_FRAMEBUFFER, attachment, textarget, texture, level);
        swapDrawFramebuffer(previous);
    }

    public static void glNamedFramebufferTexture2DEXT(int framebuffer, int attachment, int textarget, int texture, int level) {
        final int previous = swapDrawFramebuffer(framebuffer);
        glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, textarget, texture, level);
        swapDrawFramebuffer(previous);
    }

    public static void glNamedFramebufferTexture3DEXT(int framebuffer, int attachment, int textarget, int texture, int level, int zoffset) {
        final int previous = swapDrawFramebuffer(framebuffer);
        glFramebufferTexture3D(GL30.GL_DRAW_FRAMEBUFFER, attachment, textarget, texture, level, zoffset);
        swapDrawFramebuffer(previous);
    }

    public static void glNamedFramebufferTextureFaceEXT(int framebuffer, int attachment, int texture, int level, int face) {
        glNamedFramebufferTexture2DEXT(framebuffer, attachment, face, texture, level);
    }

    private static int swapDrawFramebuffer(int framebuffer) {
        final GLContextState glCtx = ctx();
        final int previous = glCtx.drawFramebuffer;
        glCtx.drawFramebuffer = framebuffer;
        RENDER_BACKEND.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        return previous;
    }

    public static void attachTextureForReading(int attachment, int texture) {
        if (GLSMTextureLifetime.isDeferredDelete(texture)) return;
        final GLContextState glCtx = ctx();
        GLSMTextureLifetime.trackFramebufferAttachment(glCtx, glCtx.readFramebuffer, attachment, texture);
        RENDER_BACKEND.framebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, texture, 0);
    }

    public static boolean glIsFramebuffer(int framebuffer) { rejectExitCapture(); return RENDER_BACKEND.isFramebuffer(framebuffer); }

    public static boolean glIsRenderbuffer(int renderbuffer) { rejectExitCapture(); return RENDER_BACKEND.isRenderbuffer(renderbuffer); }

    public static void glBindRenderbuffer(int target, int renderbuffer) { rejectExitCapture(); RENDER_BACKEND.bindRenderbuffer(target, renderbuffer); }

    public static void glDeleteRenderbuffers(int renderbuffer) { rejectExitCapture(); RENDER_BACKEND.deleteRenderbuffers(renderbuffer); }

    public static void glRenderbufferStorage(int target, int internalformat, int width, int height) { rejectExitCapture(); RENDER_BACKEND.renderbufferStorage(target, internalformat, width, height); }

    public static void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) { rejectExitCapture(); RENDER_BACKEND.renderbufferStorageMultisample(target, samples, internalformat, width, height); }

    public static void glNamedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffertarget, int renderbuffer) {
        RENDER_BACKEND.namedFramebufferRenderbuffer(framebuffer, attachment, renderbuffertarget, renderbuffer);
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

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        rejectExitCapture();
        // A renderbuffer replaces the texture in the slot, so the slot no longer keeps a texture alive.
        GLSMTextureLifetime.beginAttach(target, attachment, 0);
        RENDER_BACKEND.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    }

    public static boolean framebufferCompletenessIsMeaningful() { return RENDER_BACKEND.framebufferCompletenessIsMeaningful(); }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        rejectExitCapture();
            type = remapPixelTypeForGLES(format, type);
            suspendPixelPackBuffer();
            RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixels);
            restorePixelPackBuffer();
        }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        rejectExitCapture();
            type = remapPixelTypeForGLES(format, type);
            suspendPixelPackBuffer();
            RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixels);
            restorePixelPackBuffer();
        }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        rejectExitCapture();
            type = remapPixelTypeForGLES(format, type);
            suspendPixelPackBuffer();
            RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixels);
            restorePixelPackBuffer();
        }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {
        rejectExitCapture();
            type = remapPixelTypeForGLES(format, type);
            RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixelBufferOffset);
        }

    public static void glFramebufferTexture(int target, int attachment, int texture, int level) {
        rejectExitCapture();
        if (!GLSMTextureLifetime.beginAttach(target, attachment, texture)) return;
        RENDER_BACKEND.framebufferTexture(target, attachment, texture, level);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        rejectExitCapture();
            final boolean cacheTrusted = isGpuDiagnosticCacheTrusted();
            final int diagnosticReadFramebuffer = GpuCommandDiagnostics.trustedValue(cacheTrusted, ctx().readFramebuffer);
            final int diagnosticDrawFramebuffer = GpuCommandDiagnostics.trustedValue(cacheTrusted, ctx().drawFramebuffer);
            recordGpuCommand(
                GpuCommandType.BLIT_FRAMEBUFFER,
                GpuCommandPhase.BEGIN,
                diagnosticReadFramebuffer,
                diagnosticDrawFramebuffer
            );
            RENDER_BACKEND.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            recordGpuCommand(
                GpuCommandType.BLIT_FRAMEBUFFER,
                GpuCommandPhase.END,
                diagnosticReadFramebuffer,
                diagnosticDrawFramebuffer
            );
            gpuCheckpoint(GpuCommandType.BLIT_FRAMEBUFFER);
        }

    public static boolean isFramebufferEnabled() {
            return initConfig != null && initConfig.isFramebufferSupported() && initConfig.isFboEnabled();
        }

    public static int getDrawFramebufferGeneration() {
            return ctx().drawFramebufferGeneration;
        }

    public static IntegerStateStack getDrawBuffer() {
            return ctx().drawBuffer;
        }

    public static int getDrawFramebuffer() {
            return ctx().drawFramebuffer;
        }

    public static int getReadFramebuffer() {
            return ctx().readFramebuffer;
        }
}
