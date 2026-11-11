package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.device.SDLDrawable;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextGL;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.Drawable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.SharedDrawable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Routes legacy display and shared drawable operations to the selected renderer. */
public final class SDLGPUDisplayBridge {
    private static final Set<SharedDrawable> DESTROYED_SHARED_DRAWABLES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final ThreadLocal<Object> CURRENT_DRAWABLE = new ThreadLocal<>();

    private SDLGPUDisplayBridge() {}

    public static void ensureDrawableInstalled() {
        if (!(Display.getDrawable() instanceof SDLDrawable)) {
            throw new IllegalStateException("SDL GPU display drawable was not installed before context initialization");
        }
    }

    public static boolean isSdlDrawable(SharedDrawable drawable) {
        Drawable source = drawable;
        while (source instanceof SharedDrawable shared) source = shared.getSharingDrawable();
        return source instanceof SDLDrawable;
    }

    public static boolean isSdlSharedDrawable(Object drawable) {
        return drawable instanceof SharedDrawable shared && isSdlDrawable(shared);
    }

    public static void present() {
        BackendManager.RENDER_BACKEND.handleSwapBuffers();
    }

    public static void releaseRenderThread() {
        BackendManager.RENDER_BACKEND.onRenderThreadReleased(Thread.currentThread());
    }

    public static void makeCurrent(Drawable drawable) throws LWJGLException {
        if (DESTROYED_SHARED_DRAWABLES.contains(drawable)) throw new IllegalStateException("The SDL shared drawable has been destroyed");
        GLStateManager.makeCurrent(drawable);
    }

    public static void releaseContext(Drawable drawable) throws LWJGLException {
        if (isSdlSharedDrawable(drawable) && CURRENT_DRAWABLE.get() != drawable) return;
        GLStateManager.releaseContext(drawable);
    }

    public static void onMadeCurrent(Object drawable) { CURRENT_DRAWABLE.set(drawable); }

    public static void onReleased(Object drawable) {
        if (CURRENT_DRAWABLE.get() == drawable) CURRENT_DRAWABLE.remove();
    }

    public static void clearCurrentDrawable() { CURRENT_DRAWABLE.remove(); }

    public static boolean isCurrent(Drawable drawable) {
        if (drawable instanceof SDLDrawable sdl && sdl.isDestroyed()) return false;
        if (DESTROYED_SHARED_DRAWABLES.contains(drawable)) return false;
        return CURRENT_DRAWABLE.get() == drawable && BackendManager.RENDER_BACKEND.hasContextOnThread();
    }

    public static void destroySharedDrawable(SharedDrawable drawable) throws LWJGLException {
        if (DESTROYED_SHARED_DRAWABLES.contains(drawable)) return;
        releaseContext(drawable);
        ContextGL token = drawable.getContext();
        GLStateManager.retireDrawableContext(token);
        DESTROYED_SHARED_DRAWABLES.add(drawable);
    }

    public static void setSwapInterval(int interval) {
        BackendManager.RENDER_BACKEND.setVSyncEnabled(interval != 0);
    }

    public static String adapter() { return BackendManager.RENDER_BACKEND.getString(GL11.GL_VENDOR); }
    public static String version() { return BackendManager.RENDER_BACKEND.getString(GL11.GL_VERSION); }
}
