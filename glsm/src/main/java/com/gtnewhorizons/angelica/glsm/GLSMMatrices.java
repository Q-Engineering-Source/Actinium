package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.vector.Quaternion;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.LOGGER;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.bumpMatrixGeneration;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.conversionMatrix4d;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.conversionMatrix4f;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getMatrixStack;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.initConfig;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.multMatrix;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.rotation;

public final class GLSMMatrices {

    private GLSMMatrices() {}

    public static void glMatrixMode(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordMatrixMode(mode);
            DisplayListManager.resetRelativeTransform();  // Mode switch is a barrier - reset delta tracking
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().matrixMode).setMode(mode);
    }

    public static void glLoadMatrix(FloatBuffer m) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final Matrix4f matrix = new Matrix4f().set(m);
            m.rewind();
            DisplayListManager.recordLoadMatrix(matrix);
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).set(m);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glLoadMatrix(DoubleBuffer m) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            conversionMatrix4d.set(m);
            m.rewind();
            final Matrix4f floatMatrix = new Matrix4f();
            floatMatrix.set(conversionMatrix4d);
            DisplayListManager.recordLoadMatrix(floatMatrix);
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            conversionMatrix4d.set(m);
            getMatrixStack(glCtx).set(conversionMatrix4d);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glLoadIdentity() {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLoadIdentity();
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).identity();
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glTranslatef(float x, float y, float z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(x, y, z, DisplayListManager.TransformOp.TRANSLATE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).translate(x, y, z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glTranslated(double x, double y, double z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform((float) x, (float) y, (float) z, DisplayListManager.TransformOp.TRANSLATE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).translate((float) x, (float) y, (float) z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glScalef(float x, float y, float z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(x, y, z, DisplayListManager.TransformOp.SCALE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).scale(x, y, z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glScaled(double x, double y, double z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform((float) x, (float) y, (float) z, DisplayListManager.TransformOp.SCALE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).scale((float) x, (float) y, (float) z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glMultMatrix(FloatBuffer floatBuffer) {
        final GLContextState glCtx = ctx();
        multMatrix.set(floatBuffer);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(multMatrix);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).mul(multMatrix);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glMultMatrix(DoubleBuffer matrix) {
        final GLContextState glCtx = ctx();
        conversionMatrix4d.set(matrix);
        conversionMatrix4f.set(conversionMatrix4d);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(conversionMatrix4f);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).mul(conversionMatrix4f);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        final float lenSq = x * x + y * y + z * z;
        if (lenSq == 0.0f) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            rotation.set(x, y, z).normalize();
            DisplayListManager.updateRelativeTransform(angle, 0, 0, DisplayListManager.TransformOp.ROTATE, rotation);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            rotation.set(x, y, z).normalize();
            getMatrixStack(glCtx).rotate((float) Math.toRadians(angle), rotation);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glRotated(double angle, double x, double y, double z) {
        final double lenSq = x * x + y * y + z * z;
        if (lenSq == 0.0) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            rotation.set((float) x, (float) y, (float) z).normalize();
            DisplayListManager.updateRelativeTransform((float) angle, 0, 0, DisplayListManager.TransformOp.ROTATE, rotation);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            rotation.set((float) x, (float) y, (float) z).normalize();
            getMatrixStack(glCtx).rotate((float) Math.toRadians(angle), rotation);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransformOrtho(left, right, bottom, top, zNear, zFar);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransformFrustum(left, right, bottom, top, zNear, zFar);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).frustum((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glPushMatrix() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPushMatrix();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            try {
                getMatrixStack(ctx()).pushMatrix();
            } catch (IllegalStateException ignored) {
                if (initConfig != null && initConfig.isLwjglDebug()) LOGGER.warn("Matrix stack overflow ", new Throwable());
            }
        }
    }

    public static void glPopMatrix() {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPopMatrix();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            try {
                getMatrixStack(glCtx).popMatrix();
                bumpMatrixGeneration(glCtx);
            } catch (IllegalStateException ignored) {
                if (initConfig != null && initConfig.isLwjglDebug()) LOGGER.warn("Matrix stack underflow ", new Throwable());
            }
        }
    }

    public static void glScalef(double x, double y, double z) {
        glScaled(x, y, z);
    }

    public static void glTranslatef(double x, double y, double z) {
        glTranslated(x, y, z);
    }

    /**
     * Double-precision angle form of {@link #glRotatef(float, float, float, float)}.
     *
     * <p>The GLSM redirector rewrites vanilla {@code GlStateManager.rotate} calls to this class by
     * method name while preserving the caller's descriptor. HBM's Nuclear Tech (Community Edition)
     * calls the {@code rotate(double, float, float, float)} overload from its tile entity item
     * renderers (issue #64); without this overload the rewritten call resolved to nothing and the
     * game crashed with {@code NoSuchMethodError}.</p>
     */
    public static void glRotatef(double angle, float x, float y, float z) {
        glRotatef((float) angle, x, y, z);
    }

    public static void glRotatef(Quaternion quaternion) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        float x = quaternion.x;
        float y = quaternion.y;
        float z = quaternion.z;
        float w = quaternion.w;
        buffer.put(1.0F - 2.0F * y * y - 2.0F * z * z);
        buffer.put(2.0F * x * y + 2.0F * w * z);
        buffer.put(2.0F * x * z - 2.0F * w * y);
        buffer.put(0.0F);
        buffer.put(2.0F * x * y - 2.0F * w * z);
        buffer.put(1.0F - 2.0F * x * x - 2.0F * z * z);
        buffer.put(2.0F * y * z + 2.0F * w * x);
        buffer.put(0.0F);
        buffer.put(2.0F * x * z + 2.0F * w * y);
        buffer.put(2.0F * y * z - 2.0F * w * x);
        buffer.put(1.0F - 2.0F * x * x - 2.0F * y * y);
        buffer.put(0.0F);
        buffer.put(0.0F);
        buffer.put(0.0F);
        buffer.put(0.0F);
        buffer.put(1.0F);
        buffer.flip();
        glMultMatrix(buffer);
    }
}
