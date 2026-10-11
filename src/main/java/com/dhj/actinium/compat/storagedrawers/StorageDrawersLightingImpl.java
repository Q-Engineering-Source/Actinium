package com.dhj.actinium.compat.storagedrawers;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.renderer.RenderHelper;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/** Implements view-space item lighting for Storage Drawers' block-item render path. */
public final class StorageDrawersLightingImpl implements StorageDrawersLighting {
    private final ThreadLocal<Matrix4f> rootModelView = new ThreadLocal<>();

    @Override
    public void captureRootModelView() {
        rootModelView.set(new Matrix4f(GLStateManager.getModelViewMatrix()));
    }

    @Override
    public void enableStandardItemLightingAtRootModelView() {
        Matrix4f capturedModelView = rootModelView.get();
        if (capturedModelView == null) {
            throw new IllegalStateException("Storage Drawers lighting matrix was not captured before item rendering");
        }
        rootModelView.remove();

        int previousMatrixMode = GLStateManager.getMatrixMode().getMode();
        FloatBuffer rootMatrixBuffer = BufferUtils.createFloatBuffer(16);
        capturedModelView.get(rootMatrixBuffer);
        rootMatrixBuffer.flip();

        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glPushMatrix();
        try {
            // glLight transforms directions at call time; drawer-local item transforms must not affect them.
            GLStateManager.glLoadMatrix(rootMatrixBuffer);
            RenderHelper.enableStandardItemLighting();
        } finally {
            GLStateManager.glPopMatrix();
            GLStateManager.glMatrixMode(previousMatrixMode);
        }
    }
}
