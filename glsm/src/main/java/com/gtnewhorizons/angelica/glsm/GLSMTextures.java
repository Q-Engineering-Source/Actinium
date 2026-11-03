package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandPhase;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import com.gtnewhorizons.angelica.glsm.recording.commands.TexImage2DCmd;
import com.gtnewhorizons.angelica.glsm.recording.commands.TexSubImage2DCmd;
import com.gtnewhorizons.angelica.glsm.states.PixelStoreState;
import com.gtnewhorizons.angelica.glsm.states.TextureBinding;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfo;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_TEXTURE_COORDS;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.changeFormatIfDeprecated;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.deferDeleteTexture;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.deferredDeleteTextures;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.flushDeferredTextureDeletes;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getActiveTextureUnit;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getActiveTextureUnitForServerState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getBoundTextureForGpuDiagnostics;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getBoundTextureForServerState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getBoundTextureInfo;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getTexImageViaFboReadPixels;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getTexParameterOrDefault;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gpuCheckpoint;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.handleRemovedTexParam;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isRecordingDisplayList;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.lockBindCallback;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.logUncachedTextureTarget;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.maybeGenerateMipmap;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.onDeleteTexture;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.packDimensions;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.recordGpuCommand;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.remapPixelTypeForGLES;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.remapTexClamp;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.remapTypeForRemappedInternalFormat;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.requireContext;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.restorePixelPackBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.restorePixelUnpackBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.shouldBypassCache;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.shouldUseDSA;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.suspendPixelPackBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.suspendPixelUnpackBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.updateTexParameterfCache;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.updateTexParameteriCache;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.warnOnce;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMTextures {

    private GLSMTextures() {}

    public static void glActiveTexture(int texture) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordActiveTexture(texture);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int newTexture = texture - GL13.GL_TEXTURE0;
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || getActiveTextureUnit() != newTexture) {
            if (caching) mod(ctx().activeTextureUnit).setValue(newTexture);
            RENDER_BACKEND.activeTexture(texture);
        }
    }

    public static void glClientActiveTexture(int texture) {
        final int unit = texture - GL13.GL_TEXTURE0;
        if (unit < 0 || unit >= MAX_TEXTURE_COORDS) {
            warnOnce("client-active-texture-" + texture,
                "glClientActiveTexture({}) is outside the {} supported texture coordinate units; ignoring", texture, MAX_TEXTURE_COORDS);
            return;
        }
        ctx().clientActiveTextureUnit = unit;
    }

    public static void glBindTexture(int target, int texture) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBindTexture(target, texture);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        // Rebinding a deferred-delete texture rescues it (Mesa/compat-profile behavior)
        if (texture != 0) {
            deferredDeleteTextures.remove(texture);
            TextureInfoCache.INSTANCE.getInfo(texture).setTarget(target);
        }

        if (target != GL11.GL_TEXTURE_2D) {
            // Non-2D targets (e.g. GL_TEXTURE_2D_ARRAY) pass through without caching
            logUncachedTextureTarget(target);
            recordGpuCommand(GpuCommandType.TEXTURE_BIND, target, texture);
            RENDER_BACKEND.bindTexture(target, texture);
            return;
        }

        if (!isCachingEnabled()) {
            final int activeUnit = getActiveTextureUnitForServerState();
            recordGpuCommand(GpuCommandType.TEXTURE_BIND, target, texture);
            RENDER_BACKEND.bindTexture(target, texture);
            GLSMHooks.notifyTextureBindSync(activeUnit, texture);
            return;
        }

        final int activeUnit = ctx().activeTextureUnit.getValue();
        final TextureBinding textureUnit = ctx().textures.getTextureUnitBindings(activeUnit);
        final int cachedBinding = textureUnit.getBinding();

        if (cachedBinding != texture) {
            recordGpuCommand(GpuCommandType.TEXTURE_BIND, target, texture);
            RENDER_BACKEND.bindTexture(target, texture);
            mod(ctx().textures.getTextureUnitBindings(activeUnit)).setBinding(texture, target);
            if (texture != 0 && activeUnit > ctx().maxBoundTextureUnit) {
                ctx().maxBoundTextureUnit = activeUnit;
            }
            if (!lockBindCallback && ctx().activeTextureUnit.getValue() == 0) {
                lockBindCallback = true;
                if (GLSMHooks.TEXTURE_BIND.hasListeners()) {
                    GLSMHooks.textureBindEvent.textureId = texture;
                    GLSMHooks.TEXTURE_BIND.post(GLSMHooks.textureBindEvent);
                }
                RenderSystem.bindTextureToUnit(0, texture);
                lockBindCallback = false;
            }
        }
        // Mirror executed binds even during GL_COMPILE_AND_EXECUTE recording and on cache hits.
        GLSMHooks.notifyTextureBindSync(activeUnit, texture);
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromIntBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            RenderSystem.textureImage2D(getBoundTextureForServerState(), target, level, internalformat, width, height, border, format, type, pixels);
        } else {
            // Non-main thread or proxy texture - use direct GL call
            RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromFloatBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromDoubleBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromByteBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels != null ? pixels.asIntBuffer() : null);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            RenderSystem.textureImage2D(getBoundTextureForServerState(), target, level, internalformat, width, height, border, format, type, pixels);
        } else {
            RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, long pixels_buffer_offset) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexImage2D with buffer offset in display lists not yet supported");
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        maybeGenerateMipmap(target, level);

    }

    public static void glDeleteTextures(int id) {
        deferDeleteTexture(id);
        onDeleteTexture(id);
    }

    public static void glDeleteTextures(IntBuffer ids) {
        for (int i = 0; i < ids.remaining(); i++) {
            final int id = ids.get(ids.position() + i);
            deferDeleteTexture(id);
            onDeleteTexture(id);
        }
    }

    public static void glDeleteTextures(int[] ids) {
        for (int id : ids) {
            glDeleteTextures(id);
        }
    }

    public static int glGenTextures() {
        flushDeferredTextureDeletes();
        return RENDER_BACKEND.genTextures();
    }

    public static void glGenTextures(IntBuffer textures) {
        flushDeferredTextureDeletes();
        RENDER_BACKEND.genTextures(textures);
    }

    public static void glGenTextures(int[] textures) {
        requireContext("glGenTextures");
        flushDeferredTextureDeletes();
        for (int i = 0; i < textures.length; i++) textures[i] = RENDER_BACKEND.genTextures();
    }

    public static void glTexParameter(int target, int pname, IntBuffer params) {
        if (handleRemovedTexParam(target, pname, params.remaining() >= 1 ? params.get(params.position()) : 0)) return;
        if (params.remaining() >= 1) {
            final int val = params.get(params.position());
            final int remapped = remapTexClamp(pname, val);
            if (remapped != val) params.put(params.position(), remapped);
            if (target == GL11.GL_TEXTURE_2D) {
                updateTexParameteriCache(getBoundTextureInfo(), pname, remapped);
            }
        }
        RENDER_BACKEND.texParameteriv(target, pname, params);
    }

    public static void glTexParameter(int target, int pname, FloatBuffer params) {
        if (handleRemovedTexParam(target, pname, params.remaining() >= 1 ? (int) params.get(params.position()) : 0)) return;
        if (params.remaining() >= 1) {
            final float val = params.get(params.position());
            final float remapped = remapTexClamp(pname, val);
            if (remapped != val) params.put(params.position(), remapped);
            if (target == GL11.GL_TEXTURE_2D) {
                updateTexParameterfCache(getBoundTextureInfo(), pname, remapped);
            }
        }
        RENDER_BACKEND.texParameterfv(target, pname, params);
    }

    public static void glTexParameteri(int target, int pname, int param) {
        if (handleRemovedTexParam(target, pname, param)) return;
        param = remapTexClamp(pname, param);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordTexParameteri(target, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (target != GL11.GL_TEXTURE_2D) {
            RENDER_BACKEND.texParameteri(target, pname, param);
            return;
        }
        if (!updateTexParameteriCache(getBoundTextureInfo(), pname, param)) return;

        RENDER_BACKEND.texParameteri(target, pname, param);
    }

    public static void glTexParameterf(int target, int pname, float param) {
        if (handleRemovedTexParam(target, pname, param > 0 ? (int) (param + 0.5f) : (int) (param - 0.5f))) return;
        param = remapTexClamp(pname, param);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordTexParameterf(target, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (target != GL11.GL_TEXTURE_2D) {
            RENDER_BACKEND.texParameterf(target, pname, param);
            return;
        }
        if (!updateTexParameterfCache(target, getBoundTextureForServerState(), pname, param)) return;

        RENDER_BACKEND.texParameterf(target, pname, param);
    }

    public static int glGetTexParameteri(int target, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return RENDER_BACKEND.getTexParameteri(target, pname);
        }
        return getTexParameterOrDefault(getBoundTextureForServerState(), pname, () -> RENDER_BACKEND.getTexParameteri(target, pname));
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return RENDER_BACKEND.getTexLevelParameteri(target, level, pname);
        }
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(getBoundTextureForServerState());
        if (info == null) {
            if (isRecordingDisplayList()) {
                throw new IllegalStateException(String.format(
                    "glGetTexLevelParameteri called during display list recording with no cached TextureInfo for texture %d. " +
                    "Cannot query OpenGL state during compilation!", getBoundTextureForServerState()));
            }
            return RENDER_BACKEND.getTexLevelParameteri(target, level, pname);
        }
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> info.getWidth();
            case GL11.GL_TEXTURE_HEIGHT -> info.getHeight();
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> info.getInternalFormat();
            default -> {
                if (isRecordingDisplayList()) {
                    throw new IllegalStateException(String.format(
                        "glGetTexLevelParameteri called during display list recording with uncached pname 0x%s for texture %d. " +
                        "Cannot query OpenGL state during compilation!", Integer.toHexString(pname), getBoundTextureForServerState()));
                }
                yield RENDER_BACKEND.getTexLevelParameteri(target, level, pname);
            }
        };
    }

    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        if (RenderSystem.isGLES()) {
            getTexImageViaFboReadPixels(target, level, format, type, pixels);
            return;
        }
        suspendPixelPackBuffer();
        RENDER_BACKEND.getTexImage(target, level, format, type, pixels);
        restorePixelPackBuffer();
    }

    public static void glGetTexImage(int target, int level, int format, int type, IntBuffer pixels) {
        if (RenderSystem.isGLES()) {
            getTexImageViaFboReadPixels(target, level, format, type, pixels);
            return;
        }
        suspendPixelPackBuffer();
        RENDER_BACKEND.getTexImage(target, level, format, type, pixels);
        restorePixelPackBuffer();
    }

    public static void glBindTextureUnit(int unit, int texture) {
        if (getBoundTextureForServerState(unit) == texture) return;
        recordGpuCommand(GpuCommandType.TEXTURE_BIND, 0, texture);
        RENDER_BACKEND.bindTextureUnit(unit, texture);
        mod(ctx().textures.getTextureUnitBindings(unit)).setBinding(texture);
        if (texture != 0 && unit > ctx().maxBoundTextureUnit) {
            ctx().maxBoundTextureUnit = unit;
        }
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexSubImage2DCmd.fromByteBuffer(target, level, xoffset, yoffset, width, height, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            // Use DSA to upload directly to the texture - keeps GL binding state unchanged
            RenderSystem.textureSubImage2D(getBoundTextureForServerState(), target, level, xoffset, yoffset, width, height, format, type, pixels);
        } else {
            // Non-main thread or proxy texture - use direct GL call
            RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_2D);
        TextureInfoCache.INSTANCE.onTexSubImage2D(target, level);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexSubImage2DCmd.fromIntBuffer(target, level, xoffset, yoffset, width, height, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            RenderSystem.textureSubImage2D(getBoundTextureForServerState(), target, level, xoffset, yoffset, width, height, format, type, pixels);
        } else {
            // Non-main thread or proxy texture - use direct GL call
            RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_2D);
        TextureInfoCache.INSTANCE.onTexSubImage2D(target, level);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels_buffer_offset) {
        type = remapPixelTypeForGLES(format, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexSubImage2D with buffer offset in display lists not yet supported");
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels_buffer_offset);
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_2D);
        TextureInfoCache.INSTANCE.onTexSubImage2D(target, level);
        maybeGenerateMipmap(target, level);

    }

    public static void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexImage2D in display lists not yet implemented - if you see this, please report!");
        }
        recordGpuCommand(GpuCommandType.COPY_TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.copyTexImage2D(target, level, internalFormat, x, y, width, height, border);
        recordGpuCommand(GpuCommandType.COPY_TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.COPY_TEX_IMAGE_2D);
    }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexSubImage2D in display lists not yet implemented - if you see this, please report!");
        }
        recordGpuCommand(GpuCommandType.COPY_TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
        recordGpuCommand(GpuCommandType.COPY_TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.COPY_TEX_SUB_IMAGE_2D);
    }

    public static void glPixelStorei(int pname, int param) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glPixelStorei in display lists not yet implemented - if you see this, please report!");
        }
        if (isCachingEnabled()) {
            if (PixelStoreState.isPack(pname)) {
                ctx().pixelPackState = ctx().pixelPackState.with(pname, param);
            } else {
                ctx().pixelUnpackState = ctx().pixelUnpackState.with(pname, param);
            }
        }
        RENDER_BACKEND.pixelStorei(pname, param);
    }

    public static void glCopyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                           int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                           int srcWidth, int srcHeight, int srcDepth) {
        recordGpuCommand(GpuCommandType.COPY_IMAGE_SUB_DATA, GpuCommandPhase.BEGIN, srcName, dstName);
        RENDER_BACKEND.copyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ,
            dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
        recordGpuCommand(GpuCommandType.COPY_IMAGE_SUB_DATA, GpuCommandPhase.END, srcName, dstName);
        gpuCheckpoint(GpuCommandType.COPY_IMAGE_SUB_DATA);
    }

    /**
     * Reads a texture into the bound pixel-pack buffer at a byte offset. Unlike the client-buffer
     * overloads, this must preserve the PBO binding so that offset zero is not a null CPU pointer.
     */
    public static void glGetTexImage(int target, int level, int format, int type, long pixelBufferOffset) {
        RENDER_BACKEND.getTexImage(target, level, format, type, pixelBufferOffset);
    }
}
