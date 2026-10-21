package com.gtnewhorizons.angelica.glsm.context;

import java.util.List;

/**
 * Tracks native context lifetimes and the context current on each calling thread.
 *
 * <p>GLSM uses this registry to distinguish a context from a recycled native handle, preserve identity when a
 * context moves between threads, and retire its state only after its owner destroys it.</p>
 */
public interface GLContextRegistry {

    /**
     * Binds a context to the calling thread after its native {@code makeCurrent} call succeeds.
     *
     * @param contextToken opaque identity of the host context object
     * @param nativeHandle native handle that the backend reports as current
     * @return the stable identity for this native context lifetime
     */
    GLContextIdentity bindCurrent(Object contextToken, long nativeHandle);

    /**
     * Records that {@code contextToken} was created sharing objects with {@code sharedWithToken}.
     *
     * <p>This relationship must be recorded before the child context is first bound. Registering the child
     * also preserves the share-group identity if an intermediate context is destroyed before the child is
     * first made current.</p>
     *
     * @param contextToken opaque identity of the newly created child context
     * @param nativeHandle native handle assigned to the newly created child context
     * @param sharedWithToken opaque identity of the context it shares objects with
     * @return the stable identity for the child context lifetime
     */
    GLContextIdentity registerSharedContext(Object contextToken, long nativeHandle, Object sharedWithToken);

    /**
     * Clears the calling thread's current-context binding after native release or a native switch.
     *
     * <p>Releasing a context leaves its identity and cached state live for a later bind.</p>
     */
    void releaseCurrent();

    /**
     * Returns the context identity bound to the calling thread without querying the native backend.
     *
     * <p>This is only a cheap cache gate. State access must use {@link #current(long, boolean)} to validate the
     * backend's current-context report.</p>
     */
    GLContextIdentity currentBinding();

    /** Returns a live context's identity by host token without binding or registering it. */
    GLContextIdentity identityFor(Object contextToken);

    /**
     * Returns the calling thread's context identity, checking it against a backend handle when available.
     *
     * @param observedNativeHandle current native handle, or zero when the backend cannot report one
     * @param nativeContextCurrent whether the backend confirms a context is current; false clears a stale
     *                             thread binding, while true with handle zero uses the registered identity
     *                             without a handle comparison
     * @return the current identity, or {@code null} when the thread has no registered current context
     */
    GLContextIdentity current(long observedNativeHandle, boolean nativeContextCurrent);

    /**
     * Retires one context after its native destruction has completed.
     *
     * <p>The registry forgets context-local state ownership but performs no GL deletion. GL has already
     * reclaimed objects owned only by a destroyed context, and deleting the same integer name in another
     * context could destroy an unrelated object.</p>
     *
     * @param contextToken opaque identity of the destroyed host context object
     * @return the retired identity, or {@code null} when the context was never registered
     */
    GLContextIdentity retire(Object contextToken);

    /** Returns whether the supplied identity still names a live native context. */
    boolean isLive(GLContextIdentity identity);

    /** Returns the live context identities that share the requested OpenGL object namespace. */
    List<GLContextIdentity> liveContextsInShareGroup(long shareGroupId);

    /**
     * Installs the owner that drops GLSM's CPU-side state when a native context is retired.
     *
     * <p>The callback runs after registry locks have been released. Only one state owner is permitted.</p>
     */
    void setRetirementListener(ContextRetirementListener listener);

    /**
     * Subscribes an independent resource owner to native context retirement events.
     *
     * @param listener owner that must discard resources after a context lifetime ends
     * @return a handle that removes this subscription when closed
     */
    AutoCloseable addRetirementListener(ContextRetirementListener listener);
}
