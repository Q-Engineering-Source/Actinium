package com.gtnewhorizons.angelica.sdlgpu.device;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUDisplayBridge;
import com.gtnewhorizons.angelica.sdlgpu.SDLHostHooks;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextGL;
import org.lwjgl.opengl.DrawableGL;
import org.lwjgl.opengl.GL11;

/** Provides a stable context token while SDL GPU owns native rendering. */
public final class SDLDrawable extends DrawableGL {
    private boolean destroyed;

    public SDLDrawable() {
        super(new ContextGL(0L, 0L, true));
    }

    @Override public void makeCurrent() throws LWJGLException {
        if (destroyed) throw new IllegalStateException("The SDL drawable has been destroyed");
        SDLGPUDisplayBridge.makeCurrent(this);
    }

    @Override public void releaseContext() throws LWJGLException {
        SDLGPUDisplayBridge.releaseContext(this);
    }

    @Override public boolean isCurrent() { return SDLGPUDisplayBridge.isCurrent(this); }

    @Override public ContextGL createSharedContext() {
        if (destroyed) throw new IllegalStateException("The SDL drawable has been destroyed");
        return new ContextGL(0L, 0L, true);
    }

    @Override public void destroy() {
        if (destroyed) return;
        SDLGPUDisplayBridge.releaseRenderThread();
        SDLGPUDisplayBridge.onReleased(this);
        GLStateManager.retireDisplayContext(getContext());
        destroyed = true;
    }

    public boolean isDestroyed() { return destroyed; }
    @Override public void checkGLError() { GLStateManager.checkGLError(); }
    @Override public void setSwapInterval(int interval) { SDLGPUDisplayBridge.setSwapInterval(interval); }
    @Override public void swapBuffers() { SDLGPUDisplayBridge.present(); }
    @Override public void initContext(float red, float green, float blue) {
        GLStateManager.glClearColor(red, green, blue, 0.0f);
        GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }
    public long getSdlWindowId() { return SDLHostHooks.window(); }
}
