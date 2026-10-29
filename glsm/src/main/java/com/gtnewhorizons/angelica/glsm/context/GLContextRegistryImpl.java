package com.gtnewhorizons.angelica.glsm.context;

import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Identity-based implementation of native context lifetime tracking. */
final class GLContextRegistryImpl implements GLContextRegistry {
    private static final Logger LOGGER = LogManager.getLogger("GLSMContextMemory");

    private final IdentityHashMap<Object, ContextEntry> contextsByToken = new IdentityHashMap<>();
    private final IdentityHashMap<Object, ShareGroup> shareGroupsByToken = new IdentityHashMap<>();
    private final Map<Long, ContextEntry> contextsByHandle = new HashMap<>();
    private final Map<GLContextIdentity, Thread> ownersByIdentity = new HashMap<>();
    private final CopyOnWriteArrayList<ContextRetirementListener> additionalRetirementListeners = new CopyOnWriteArrayList<>();
    private final ThreadLocal<ContextEntry> currentContext = new ThreadLocal<>();
    private volatile ContextRetirementListener retirementListener;
    private long nextGeneration = 1L;
    private long nextShareGroupId = 1L;

    @Override
    public GLContextIdentity bindCurrent(Object contextToken, long nativeHandle) {
        Objects.requireNonNull(contextToken, "contextToken");
        if (nativeHandle <= 0L) {
            throw new IllegalArgumentException("Cannot bind a context with an invalid native handle: " + nativeHandle);
        }

        final GLContextIdentity retired;
        final ContextRetirementListener primaryListener;
        final List<ContextRetirementListener> additionalListeners;
        final GLContextIdentity bound;
        synchronized (this) {
            ContextEntry entry = contextsByToken.get(contextToken);
            final ContextEntry handleOwner = contextsByHandle.get(nativeHandle);
            if (handleOwner != null && handleOwner != entry) {
                throw new IllegalStateException(
                    "Native context handle 0x" + Long.toHexString(nativeHandle)
                        + " is still owned by a different context lifetime " + handleOwner.identity.generation());
            }

            retired = entry != null && entry.identity.nativeHandle() != nativeHandle ? entry.identity : null;
            if (retired != null) {
                assertNotOwnedByAnotherThread(entry);
                retireEntry(entry);
                entry = null;
                primaryListener = retirementListener;
                additionalListeners = List.copyOf(additionalRetirementListeners);
            } else {
                primaryListener = null;
                additionalListeners = List.of();
            }

            if (entry == null) {
                entry = createEntry(contextToken, nativeHandle);
            }

            final ContextEntry previous = currentContext.get();
            if (previous != null && previous != entry) {
                releaseCurrentEntry(previous);
            }

            final Thread owner = ownersByIdentity.get(entry.identity);
            if (owner != null && owner != Thread.currentThread()) {
                throw new IllegalStateException(
                    "OpenGL context generation " + entry.identity.generation()
                        + " is already current on thread " + owner.getName());
            }
            currentContext.set(entry);
            ownersByIdentity.put(entry.identity, Thread.currentThread());
            bound = entry.identity;
        }

        notifyRetired(retired, primaryListener, additionalListeners);
        return bound;
    }

    @Override
    public synchronized GLContextIdentity registerSharedContext(
        Object contextToken,
        long nativeHandle,
        Object sharedWithToken
    ) {
        Objects.requireNonNull(contextToken, "contextToken");
        Objects.requireNonNull(sharedWithToken, "sharedWithToken");
        if (contextToken == sharedWithToken) {
            throw new IllegalArgumentException("A context cannot share with itself");
        }
        if (nativeHandle <= 0L) {
            throw new IllegalArgumentException("Cannot register a shared context with an invalid native handle: " + nativeHandle);
        }
        final ShareGroup parentGroup = shareGroupsByToken.get(sharedWithToken);
        if (parentGroup == null) {
            throw new IllegalStateException("Cannot share with an unregistered or retired context");
        }
        final ContextEntry existingChild = contextsByToken.get(contextToken);
        if (existingChild != null) {
            if (existingChild.identity.nativeHandle() == nativeHandle && existingChild.shareGroup == parentGroup) {
                return existingChild.identity;
            }
            throw new IllegalStateException("A live child context cannot be assigned to another share group");
        }
        final ContextEntry handleOwner = contextsByHandle.get(nativeHandle);
        if (handleOwner != null) {
            throw new IllegalStateException(
                "Native context handle 0x" + Long.toHexString(nativeHandle)
                    + " is still owned by context generation " + handleOwner.identity.generation());
        }
        final ShareGroup previous = shareGroupsByToken.putIfAbsent(contextToken, parentGroup);
        if (previous != null && previous != parentGroup) {
            throw new IllegalStateException("Context was already assigned to a different share group");
        }
        final ContextEntry child = createEntry(contextToken, nativeHandle, parentGroup);
        return child.identity;
    }

    @Override
    public synchronized void releaseCurrent() {
        final ContextEntry entry = currentContext.get();
        if (entry != null) {
            releaseCurrentEntry(entry);
        }
    }

    @Override
    public GLContextIdentity currentBinding() {
        final ContextEntry entry = currentContext.get();
        return entry == null ? null : entry.identity;
    }

    @Override
    public synchronized GLContextIdentity identityFor(Object contextToken) {
        Objects.requireNonNull(contextToken, "contextToken");
        final ContextEntry entry = contextsByToken.get(contextToken);
        return entry == null ? null : entry.identity;
    }

    @Override
    public synchronized GLContextIdentity current(long observedNativeHandle, boolean nativeContextCurrent) {
        final ContextEntry entry = currentContext.get();
        if (!nativeContextCurrent) {
            if (entry != null) {
                releaseCurrentEntry(entry);
            }
            return null;
        }
        if (entry == null) {
            if (observedNativeHandle != 0L) {
                throw new IllegalStateException(
                    "Native context 0x" + Long.toHexString(observedNativeHandle)
                        + " is current but has no GLSM identity binding");
            }
            throw new IllegalStateException("A native context is current but the backend cannot identify it");
        }
        if (observedNativeHandle != 0L && entry.identity.nativeHandle() != observedNativeHandle) {
            throw new IllegalStateException(
                "GLSM context generation " + entry.identity.generation() + " belongs to native handle 0x"
                    + Long.toHexString(entry.identity.nativeHandle()) + " but the backend reports 0x"
                    + Long.toHexString(observedNativeHandle));
        }
        return entry.identity;
    }

    @Override
    public GLContextIdentity retire(Object contextToken) {
        Objects.requireNonNull(contextToken, "contextToken");
        final GLContextIdentity retired;
        final ContextRetirementListener listener;
        final List<ContextRetirementListener> additionalListeners;
        synchronized (this) {
            final ContextEntry entry = contextsByToken.get(contextToken);
            if (entry == null) {
                shareGroupsByToken.remove(contextToken);
                return null;
            }

            assertNotOwnedByAnotherThread(entry);
            if (currentContext.get() == entry) {
                releaseCurrentEntry(entry);
            }
            retireEntry(entry);
            retired = entry.identity;
            listener = retirementListener;
            additionalListeners = List.copyOf(additionalRetirementListeners);
        }

        notifyRetired(retired, listener, additionalListeners);
        return retired;
    }

    private static void notifyRetired(
        GLContextIdentity retired,
        ContextRetirementListener primaryListener,
        List<ContextRetirementListener> additionalListeners
    ) {
        if (retired == null) {
            return;
        }
        if (primaryListener != null) {
            primaryListener.onContextRetired(retired);
        }
        for (ContextRetirementListener additionalListener : additionalListeners) {
            additionalListener.onContextRetired(retired);
        }
    }

    @Override
    public synchronized boolean isLive(GLContextIdentity identity) {
        if (identity == null) {
            return false;
        }
        return contextsByToken.values().stream().anyMatch(entry -> entry.identity.equals(identity));
    }

    @Override
    public synchronized List<GLContextIdentity> liveContextsInShareGroup(long shareGroupId) {
        final List<GLContextIdentity> identities = new ArrayList<>();
        for (ContextEntry entry : contextsByToken.values()) {
            if (entry.identity.shareGroupId() == shareGroupId) {
                identities.add(entry.identity);
            }
        }
        return List.copyOf(identities);
    }

    @Override
    public synchronized void setRetirementListener(ContextRetirementListener listener) {
        retirementListener = listener;
    }

    @Override
    public AutoCloseable addRetirementListener(ContextRetirementListener listener) {
        Objects.requireNonNull(listener, "listener");
        additionalRetirementListeners.add(listener);
        return () -> additionalRetirementListeners.remove(listener);
    }

    private ContextEntry createEntry(Object contextToken, long nativeHandle) {
        final ShareGroup shareGroup = shareGroupFor(contextToken);
        return createEntry(contextToken, nativeHandle, shareGroup);
    }

    private ContextEntry createEntry(Object contextToken, long nativeHandle, ShareGroup shareGroup) {
        final GLContextIdentity identity = new GLContextIdentity(nextGeneration++, nativeHandle, shareGroup.id);
        final ContextEntry entry = new ContextEntry(contextToken, shareGroup, identity);
        contextsByToken.put(contextToken, entry);
        contextsByHandle.put(nativeHandle, entry);
        if (GLSMPerfDebug.isEnabled()) {
            LOGGER.info("[ActiniumMemory][context] event=create generation={} shareGroup={} nativeHandle=0x{} liveContexts={}",
                identity.generation(), identity.shareGroupId(), Long.toHexString(nativeHandle), contextsByToken.size());
        }
        return entry;
    }

    private ShareGroup shareGroupFor(Object contextToken) {
        final ShareGroup existing = shareGroupsByToken.get(contextToken);
        if (existing != null) {
            return existing;
        }
        final ShareGroup created = new ShareGroup(nextShareGroupId++);
        shareGroupsByToken.put(contextToken, created);
        return created;
    }

    private void assertNotOwnedByAnotherThread(ContextEntry entry) {
        final Thread owner = ownersByIdentity.get(entry.identity);
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException(
                "Cannot retire context generation " + entry.identity.generation()
                    + " while it is current on thread " + owner.getName());
        }
    }

    private void releaseCurrentEntry(ContextEntry entry) {
        currentContext.remove();
        ownersByIdentity.remove(entry.identity, Thread.currentThread());
    }

    private void retireEntry(ContextEntry entry) {
        contextsByToken.remove(entry.ownerToken);
        contextsByHandle.remove(entry.identity.nativeHandle(), entry);
        shareGroupsByToken.remove(entry.ownerToken, entry.shareGroup);
        if (currentContext.get() == entry) {
            releaseCurrentEntry(entry);
        }
        shareGroupsByToken.entrySet().removeIf(link -> link.getValue() == entry.shareGroup
            && !contextsByToken.containsKey(link.getKey()));
        if (GLSMPerfDebug.isEnabled()) {
            LOGGER.info("[ActiniumMemory][context] event=retire generation={} shareGroup={} nativeHandle=0x{} liveContexts={}",
                entry.identity.generation(), entry.identity.shareGroupId(),
                Long.toHexString(entry.identity.nativeHandle()), contextsByToken.size());
        }
    }

    private static final class ContextEntry {
        private final Object ownerToken;
        private final ShareGroup shareGroup;
        private final GLContextIdentity identity;

        private ContextEntry(Object ownerToken, ShareGroup shareGroup, GLContextIdentity identity) {
            this.ownerToken = ownerToken;
            this.shareGroup = shareGroup;
            this.identity = identity;
        }
    }

    private static final class ShareGroup {
        private final long id;

        private ShareGroup(long id) {
            this.id = id;
        }
    }
}
