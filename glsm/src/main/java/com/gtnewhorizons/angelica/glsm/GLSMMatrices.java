package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.MatrixModeStack;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import lombok.SneakyThrows;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;
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
import static com.gtnewhorizons.angelica.glsm.GLSMTextures.glGenerateMipmap;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAT4_STACK_CURR_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.glTexImage2D;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gluBuffer;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gluMatrix2;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gluMatrix;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.gluVec4;

public final class GLSMMatrices {

    private GLSMMatrices() {}

    /** Absolute changes replace the matrix, so a capture cannot model them as a relative edit. */
    private static void beforeMatrixChange(GLContextState c, boolean absolute) {
        c.exitCapture.matrixChange(absolute);
    }

    public static void glMatrixMode(int mode) {
        final GLContextState glCtx = ctx();
        glCtx.exitCapture.write(ExitStateCapture.MATRIX_MODE);
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordMatrixMode(mode);
            DisplayListManager.resetRelativeTransform();  // Mode switch is a barrier - reset delta tracking
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(glCtx.matrixMode).setMode(mode);
    }

    public static void glLoadMatrix(FloatBuffer m) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, true);
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
            GLStateManager.getMatrixStack(glCtx).set(m);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glLoadMatrix(DoubleBuffer m) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, true);
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
            GLStateManager.getMatrixStack(glCtx).set(conversionMatrix4d);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glLoadIdentity() {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, true);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLoadIdentity();
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).identity();
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glTranslatef(float x, float y, float z) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(x, y, z, DisplayListManager.TransformOp.TRANSLATE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).translate(x, y, z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glTranslated(double x, double y, double z) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform((float) x, (float) y, (float) z, DisplayListManager.TransformOp.TRANSLATE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).translate((float) x, (float) y, (float) z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glScalef(float x, float y, float z) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(x, y, z, DisplayListManager.TransformOp.SCALE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).scale(x, y, z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glScaled(double x, double y, double z) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform((float) x, (float) y, (float) z, DisplayListManager.TransformOp.SCALE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).scale((float) x, (float) y, (float) z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glMultMatrix(FloatBuffer floatBuffer) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        multMatrix.set(floatBuffer);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(multMatrix);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).mul(multMatrix);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glMultMatrix(DoubleBuffer matrix) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        conversionMatrix4d.set(matrix);
        conversionMatrix4f.set(conversionMatrix4d);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(conversionMatrix4f);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).mul(conversionMatrix4f);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        final float lenSq = x * x + y * y + z * z;
        if (lenSq == 0.0f) return;
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            rotation.set(x, y, z).normalize();
            DisplayListManager.updateRelativeTransform(angle, 0, 0, DisplayListManager.TransformOp.ROTATE, rotation);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            rotation.set(x, y, z).normalize();
            GLStateManager.getMatrixStack(glCtx).rotate((float) Math.toRadians(angle), rotation);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glRotated(double angle, double x, double y, double z) {
        final double lenSq = x * x + y * y + z * z;
        if (lenSq == 0.0) return;
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            rotation.set((float) x, (float) y, (float) z).normalize();
            DisplayListManager.updateRelativeTransform((float) angle, 0, 0, DisplayListManager.TransformOp.ROTATE, rotation);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            rotation.set((float) x, (float) y, (float) z).normalize();
            GLStateManager.getMatrixStack(glCtx).rotate((float) Math.toRadians(angle), rotation);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransformOrtho(left, right, bottom, top, zNear, zFar);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        final GLContextState glCtx = ctx();
        beforeMatrixChange(glCtx, false);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransformFrustum(left, right, bottom, top, zNear, zFar);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            GLStateManager.getMatrixStack(glCtx).frustum((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glPushMatrix() {
        final GLContextState glCtx = ctx();
        glCtx.exitCapture.pushMatrix();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPushMatrix();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            try {
                getMatrixStack(glCtx).pushMatrix();
            } catch (IllegalStateException ignored) {
                glCtx.exitCapture.reject();
                if (initConfig != null && initConfig.isLwjglDebug()) LOGGER.warn("Matrix stack overflow ", new Throwable());
            }
        }
    }

    public static void glPopMatrix() {
        final GLContextState glCtx = ctx();
        glCtx.exitCapture.popMatrix();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPopMatrix();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            try {
                GLStateManager.getMatrixStack(glCtx).popMatrix();
                bumpMatrixGeneration(glCtx);
            } catch (IllegalStateException ignored) {
                glCtx.exitCapture.reject();
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

    @SneakyThrows
        public static int getMatrixStackDepth(Matrix4fStack stack) {
            // JOML's curr is 0-based (0 = no pushes), but OpenGL starts at 1 (base matrix counts)
            return (int) MAT4_STACK_CURR_DEPTH.invokeExact(stack) + 1;
        }

    public static void setModelViewMatrix(Matrix4fc m) {
            final GLContextState glCtx = ctx();
            glCtx.exitCapture.matrixChange(GL11.GL_MODELVIEW, 0, true);
            if (isCachingEnabled()) {
                glCtx.modelViewMatrix.set(m);
                glCtx.mvGeneration++;
            }
        }

    public static void setTextureMatrix(int unit, Matrix4fc m) {
            final GLContextState glCtx = ctx();
            glCtx.exitCapture.matrixChange(GL11.GL_TEXTURE, unit, true);
            if (isCachingEnabled()) {
                glCtx.textures.getTextureUnitMatrix(unit).set(m);
                glCtx.texMatrixGeneration++;
            }
        }

    public static void setProjectionMatrix(Matrix4fc m) {
            final GLContextState glCtx = ctx();
            glCtx.exitCapture.matrixChange(GL11.GL_PROJECTION, 0, true);
            if (isCachingEnabled()) {
                glCtx.projectionMatrix.set(m);
                glCtx.projGeneration++;
            }
        }

    public static void applyMultMatrix(Matrix4f multMatrix) {
            final GLContextState glCtx = ctx();
            beforeMatrixChange(glCtx, false);
            if (isCachingEnabled()) {
                GLStateManager.getMatrixStack(glCtx).mul(multMatrix);
                bumpMatrixGeneration(glCtx);
            }
        }

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
            gluMatrix.identity().perspective((float) Math.toRadians(fovy), aspect, zNear, zFar);
            gluMatrix.get(0, gluBuffer);
            GLSMMatrices.glMultMatrix(gluBuffer);
        }

    public static void gluLookAt(float eyex, float eyey, float eyez, float centerx, float centery, float centerz, float upx, float upy, float upz) {
            gluMatrix.identity().lookAt(eyex, eyey, eyez, centerx, centery, centerz, upx, upy, upz);
            gluMatrix.get(0, gluBuffer);
            GLSMMatrices.glMultMatrix(gluBuffer);
        }

    public static void gluOrtho2D(float left, float right, float bottom, float top) {
            GLSMMatrices.glOrtho(left, right, bottom, top, -1.0, 1.0);
        }

    public static void gluPickMatrix(float x, float y, float deltaX, float deltaY, IntBuffer viewport) {
            if (deltaX <= 0 || deltaY <= 0) return;
            GLSMMatrices.glTranslatef(
                    (viewport.get(viewport.position() + 2) - 2 * (x - viewport.get(viewport.position() + 0))) / deltaX,
                    (viewport.get(viewport.position() + 3) - 2 * (y - viewport.get(viewport.position() + 1))) / deltaY,
                    0);
            GLSMMatrices.glScalef(viewport.get(viewport.position() + 2) / deltaX, viewport.get(viewport.position() + 3) / deltaY, 1.0f);
        }

    public static int gluBuild2DMipmaps(int target, int components, int width, int height, int format, int type, ByteBuffer data) {
            if (width < 1 || height < 1) return GLU.GLU_INVALID_VALUE;
            glTexImage2D(target, 0, components, width, height, 0, format, type, data);
            glGenerateMipmap(target);
            return 0;
        }

    public static String gluErrorString(int errorCode) {
            return switch (errorCode) {
                case GLU.GLU_INVALID_ENUM -> "Invalid enum (glu)";
                case GLU.GLU_INVALID_VALUE -> "Invalid value (glu)";
                case GLU.GLU_OUT_OF_MEMORY -> "Out of memory (glu)";
                case GL11.GL_NO_ERROR -> "No error";
                case GL11.GL_INVALID_ENUM -> "Invalid enum";
                case GL11.GL_INVALID_VALUE -> "Invalid value";
                case GL11.GL_INVALID_OPERATION -> "Invalid operation";
                case GL11.GL_OUT_OF_MEMORY -> "Out of memory";
                default -> "Unknown error: " + errorCode;
            };
        }

    public static boolean gluUnProject(float winx, float winy, float winz, FloatBuffer modelMatrix, FloatBuffer projMatrix, IntBuffer viewport, FloatBuffer obj_pos) {
            gluMatrix.set(projMatrix).mul(gluMatrix2.set(modelMatrix));
            if (Math.abs(gluMatrix.determinant()) < 1.0e-10f) return false;
            gluMatrix.invert(gluMatrix);

            float ndcX = (winx - viewport.get(viewport.position())) / viewport.get(viewport.position() + 2) * 2.0f - 1.0f;
            float ndcY = (winy - viewport.get(viewport.position() + 1)) / viewport.get(viewport.position() + 3) * 2.0f - 1.0f;
            float ndcZ = winz * 2.0f - 1.0f;

            gluVec4.set(ndcX, ndcY, ndcZ, 1.0f);
            gluMatrix.transform(gluVec4);
            if (gluVec4.w == 0.0f) return false;

            float invW = 1.0f / gluVec4.w;
            obj_pos.put(obj_pos.position(), gluVec4.x * invW);
            obj_pos.put(obj_pos.position() + 1, gluVec4.y * invW);
            obj_pos.put(obj_pos.position() + 2, gluVec4.z * invW);
            return true;
        }

    public static int getMvGeneration() {
            return ctx().mvGeneration;
        }

    public static int getProjGeneration() {
            return ctx().projGeneration;
        }

    public static int getTexMatrixGeneration() {
            return ctx().texMatrixGeneration;
        }

    public static MatrixModeStack getMatrixMode() {
            return ctx().matrixMode;
        }

    public static Matrix4fStack getModelViewMatrix() {
            return ctx().modelViewMatrix;
        }

    public static Matrix4fStack getProjectionMatrix() {
            return ctx().projectionMatrix;
        }
}
