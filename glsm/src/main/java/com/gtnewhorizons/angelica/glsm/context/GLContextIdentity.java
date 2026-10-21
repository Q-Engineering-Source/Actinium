package com.gtnewhorizons.angelica.glsm.context;

/**
 * Stable identity for one native OpenGL context lifetime.
 *
 * <p>The native handle lets the backend confirm which context is current. The generation keeps a newly
 * created context distinct when a platform later reuses the same handle. The share-group id identifies
 * objects OpenGL permits contexts to share; it does not make context-local objects such as VAOs shared.</p>
 *
 * @param generation unique lifetime generation assigned by {@link GLContextRegistry}
 * @param nativeHandle native handle captured after the context became current, or zero when the provider
 *                     supplies no queryable native handle
 * @param shareGroupId identity of the OpenGL share group this context belongs to
 */
public record GLContextIdentity(long generation, long nativeHandle, long shareGroupId) {
    public GLContextIdentity {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Context generation must be positive: " + generation);
        }
        if (nativeHandle <= 0L) {
            throw new IllegalArgumentException("A registered context must have a positive native handle: " + nativeHandle);
        }
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Share-group id must be positive: " + shareGroupId);
        }
    }
}
