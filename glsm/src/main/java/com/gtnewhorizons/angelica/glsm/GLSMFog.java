package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;
import org.joml.Vector3d;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.HAS_MULTIPLE_SET;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getFogColor;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getFogMode;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;

public final class GLSMFog {

    private GLSMFog() {}

    public static void glFog(int pname, FloatBuffer param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordFog(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (HAS_MULTIPLE_SET.contains(pname)) {
            if (pname == GL11.GL_FOG_COLOR && isCachingEnabled()) {
                final float red = param.get(0);
                final float green = param.get(1);
                final float blue = param.get(2);

                mod(ctx().fogState);
                ctx().fogState.getFogColor().set(red, green, blue);
                ctx().fogState.setFogAlpha(param.get(3));
                ctx().fogState.getFogColorBuffer().clear();
                ctx().fogState.getFogColorBuffer().put((FloatBuffer) param.position(0)).flip();
                ctx().fragmentGeneration++;
                if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                    GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
                }
            }
        } else {
            GLSMFog.glFogf(pname, param.get(0));
        }
    }

    public static void glFog(int pname, IntBuffer param) {
        if (HAS_MULTIPLE_SET.contains(pname)) {
            final FloatBuffer floats = BufferUtils.createFloatBuffer(param.remaining());
            for (int i = param.position(); i < param.limit(); i++) {
                floats.put((2.0f * param.get(i) + 1.0f) / Integer.MAX_VALUE);
            }
            floats.flip();
            glFog(pname, floats);
        } else {
            glFogi(pname, param.get(0));
        }
    }

    public static void glFogf(int pname, float param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordFogf(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            boolean changed = true;
            switch (pname) {
                case GL11.GL_FOG_DENSITY -> {
                    if (ctx().fogState.getDensity() != param) {
                        mod(ctx().fogState).setDensity(param);
                        ctx().fragmentGeneration++;
                    }
                }
                case GL11.GL_FOG_START -> {
                    if (ctx().fogState.getStart() != param) {
                        mod(ctx().fogState).setStart(param);
                        ctx().fragmentGeneration++;
                    }
                }
                case GL11.GL_FOG_END -> {
                    if (ctx().fogState.getEnd() != param) {
                        mod(ctx().fogState).setEnd(param);
                        ctx().fragmentGeneration++;
                    }
                }
                case GL11.GL_FOG_MODE -> {
                    if (ctx().fogState.getFogMode() != (int) param) {
                        mod(ctx().fogState).setFogMode((int) param);
                        ctx().fragmentGeneration++;
                    }
                }
                default -> changed = false;
            }
            if (changed && GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
            }
        }
    }

    public static void glFogi(int pname, int param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordFogi(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled() && pname == GL11.GL_FOG_MODE) {
            mod(ctx().fogState).setFogMode(param);
            ctx().fragmentGeneration++;
            if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
            }
        }
    }

    public static void enableFog() {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordEnable(GL11.GL_FOG);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            ctx().fogMode.enable();
            if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
            }
        }

    public static void disableFog() {
            final RecordMode mode = DisplayListManager.getRecordMode();
            if (mode != RecordMode.NONE) {
                DisplayListManager.recordDisable(GL11.GL_FOG);
                if (mode == RecordMode.COMPILE) {
                    return;
                }
            }
            ctx().fogMode.disable();
            if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
            }
        }

    public static Vector3d getFogColor() {
            return ctx().fogState.getFogColor();
        }

    public static void fogColor(float red, float green, float blue, float alpha) {
            final boolean caching = isCachingEnabled();
            if (BYPASS_CACHE || !caching || red != ctx().fogState.getFogColor().x || green != ctx().fogState.getFogColor().y || blue != ctx().fogState.getFogColor().z || alpha != ctx().fogState.getFogAlpha()) {
                if (caching) {
                    mod(ctx().fogState);
                    ctx().fogState.getFogColor().set(red, green, blue);
                    ctx().fogState.setFogAlpha(alpha);
                    ctx().fogState.getFogColorBuffer().clear();
                    ctx().fogState.getFogColorBuffer().put(red).put(green).put(blue).put(alpha).flip();
                    ctx().fragmentGeneration++;
                    if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                        GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
                    }
                }
            }
        }

    public static FogStateStack getFogState() {
            return ctx().fogState;
        }

    public static BooleanStateStack getFogMode() {
            return ctx().fogMode;
        }
}
