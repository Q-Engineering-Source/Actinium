package com.dhj.actinium.compat.lwjgl;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ContextGL;
import org.lwjgl.opengl.DrawableGL;

/** Binds LWJGLXX Display's direct GLFW context changes to GLSM's context and capture ownership. */
public final class DisplayContextBinding {
    private DisplayContextBinding() {
    }

    /** Binds the active LWJGLXX display context after a successful display operation. */
    public static void bindDisplayContextIfNativeCurrent(DrawableGL drawable) {
        final ContextGL displayContext = drawable == null ? null : drawable.getContext();
        bindIfNativeCurrent(displayContext, displayContext == null ? 0L : displayContext.glfwWindow);
    }

    /** Captures and protects the primary context before display destruction invalidates it. */
    public static ContextGL captureDisplayContextBeforeDestroy(DrawableGL drawable, boolean displayCreated) {
        final ContextGL displayContext = displayCreated && drawable != null ? drawable.getContext() : null;
        abortBeforeDisplayDestroy(displayContext, displayCreated);
        return displayContext;
    }

    /** Registers the Display context only after GLFW reports that exact native handle as current. */
    public static void bindIfNativeCurrent(Object contextToken, long contextHandle) {
        final long nativeCurrent = GLFW.glfwGetCurrentContext();
        if (nativeCurrent == 0L) {
            releaseRegistryBinding();
            return;
        }

        if (contextToken == null || contextHandle != nativeCurrent) {
            assertCurrentBindingMatches(nativeCurrent);
            return;
        }

        final var registry = GLContextRegistryProvider.get();
        final GLContextIdentity previous = registry.currentBinding();
        final GLContextIdentity target = registry.identityFor(contextToken);
        final boolean changedBinding = previous == null || !previous.equals(target);
        if (previous != null && changedBinding) {
            DisplayListManager.onContextUnbound(previous);
        }
        final GLContextIdentity bound = registry.bindCurrent(contextToken, contextHandle);
        if (changedBinding) {
            DisplayListManager.onContextBound(bound);
        }
    }

    /** Releases GLSM's thread binding only after LWJGLXX successfully clears the native context. */
    public static void releaseIfNativeUnbound() {
        if (GLFW.glfwGetCurrentContext() != 0L) {
            assertCurrentBindingMatches(GLFW.glfwGetCurrentContext());
            return;
        }
        releaseRegistryBinding();
    }

    /** Registers a newly created drawable context in the share group of its parent. */
    public static void registerSharedContext(Object contextToken, long nativeHandle, Object sharedWithToken) {
        GLContextRegistryProvider.get().registerSharedContext(contextToken, nativeHandle, sharedWithToken);
    }

    /** Aborts compilation only when ContextGL.destroy will actually destroy a shared native window. */
    public static void abortBeforeContextDestroy(Object contextToken, boolean shared, long nativeHandle) {
        if (shared && nativeHandle > 0L) {
            final GLContextIdentity identity = GLContextRegistryProvider.get().identityFor(contextToken);
            DisplayListManager.abortCompilationForContext(identity);
        }
    }

    /** Retires a shared context only after Cleanroom has invalidated its native handle. */
    public static void retireDestroyedContext(Object contextToken, long nativeHandle) {
        if (nativeHandle <= 0L) {
            GLContextRegistryProvider.get().retire(contextToken);
        }
    }

    /** Aborts primary-display recording before Display destroys its GLFW window. */
    public static void abortBeforeDisplayDestroy(Object contextToken, boolean displayCreated) {
        if (displayCreated && contextToken != null) {
            final GLContextIdentity identity = GLContextRegistryProvider.get().identityFor(contextToken);
            DisplayListManager.abortCompilationForContext(identity);
        }
    }

    /** Retires the primary display context after the captured window destruction returns. */
    public static void retireDestroyedDisplayContext(Object contextToken) {
        if (contextToken != null) {
            GLContextRegistryProvider.get().retire(contextToken);
        }
    }

    private static void releaseRegistryBinding() {
        final var registry = GLContextRegistryProvider.get();
        DisplayListManager.onContextUnbound(registry.currentBinding());
        registry.releaseCurrent();
    }

    private static void assertCurrentBindingMatches(long nativeCurrent) {
        final GLContextIdentity registered = GLContextRegistryProvider.get().currentBinding();
        if (registered == null || registered.nativeHandle() != nativeCurrent) {
            throw new IllegalStateException(
                "LWJGLXX Display returned with native context 0x" + Long.toHexString(nativeCurrent)
                    + " but no matching GLSM context identity");
        }
    }
}
