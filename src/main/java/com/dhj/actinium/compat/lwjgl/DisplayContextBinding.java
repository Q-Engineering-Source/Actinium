package com.dhj.actinium.compat.lwjgl;

import com.dhj.actinium.startup.SdlGpuBootstrap;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.context.NativeContextHandle;
import org.lwjgl.opengl.ContextGL;
import org.lwjgl.opengl.DrawableGL;

/** Binds LWJGL native context lifetime events to GLSM's per-drawable state. */
public final class DisplayContextBinding {
    private DisplayContextBinding() {
    }

    public static void bindDisplayContextIfNativeCurrent(DrawableGL drawable) {
        if (SdlGpuBootstrap.isActive()) return;
        final Object contextToken = drawable == null ? null : drawable.getContext();
        final long contextHandle = contextToken == null
            ? 0L
            : ((NativeContextHandle) contextToken).actinium$nativeHandle();
        final long nativeCurrent = WindowBackends.get().currentContextHandle();
        if (nativeCurrent == 0L) {
            GLStateManager.releaseBoundContext();
            return;
        }
        if (contextToken == null || contextHandle != nativeCurrent) {
            assertCurrentBindingMatches(nativeCurrent);
            return;
        }
        GLStateManager.bindDisplayContext(contextToken, nativeCurrent);
    }

    public static ContextGL captureDisplayContextBeforeDestroy(DrawableGL drawable, boolean displayCreated) {
        if (SdlGpuBootstrap.isActive()) return null;
        return displayCreated && drawable != null ? drawable.getContext() : null;
    }

    public static void bindIfNativeCurrent(Object contextToken, long contextHandle) {
        if (SdlGpuBootstrap.isActive()) return;
        final long nativeCurrent = WindowBackends.get().currentContextHandle();
        if (nativeCurrent == 0L) {
            GLStateManager.releaseBoundContext();
            return;
        }
        if (contextToken == null || contextHandle != nativeCurrent) {
            assertCurrentBindingMatches(nativeCurrent);
            return;
        }
        GLStateManager.bindDrawableContext(contextToken, nativeCurrent);
    }

    public static void releaseIfNativeUnbound() {
        if (SdlGpuBootstrap.isActive()) return;
        final long nativeCurrent = WindowBackends.get().currentContextHandle();
        if (nativeCurrent != 0L) {
            assertCurrentBindingMatches(nativeCurrent);
            return;
        }
        GLStateManager.releaseBoundContext();
    }

    public static void registerSharedContext(Object contextToken, long nativeHandle, Object sharedWithToken) {
        if (SdlGpuBootstrap.isActive() && nativeHandle == 0L) return;
        GLStateManager.registerDrawableContext(contextToken);
    }

    public static void abortBeforeContextDestroy(Object contextToken, boolean shared, long nativeHandle) {
        // Display-list scopes keep their existing process-wide lifetime in this context model.
    }

    public static void retireDestroyedContext(Object contextToken, long nativeHandle) {
        if (nativeHandle <= 0L) {
            GLStateManager.retireDrawableContext(contextToken);
        }
    }

    public static void retireDestroyedDisplayContext(Object contextToken) {
        if (contextToken != null) {
            GLStateManager.retireDisplayContext(contextToken);
        }
    }

    private static void assertCurrentBindingMatches(long nativeCurrent) {
        if (GLStateManager.getBoundContextHandle() != nativeCurrent) {
            throw new IllegalStateException(
                "LWJGL native context 0x" + Long.toHexString(nativeCurrent)
                    + " does not match the GLSM thread binding");
        }
    }
}
