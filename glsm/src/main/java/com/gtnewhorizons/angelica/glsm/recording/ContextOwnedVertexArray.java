package com.gtnewhorizons.angelica.glsm.recording;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import org.lwjgl.opengl.GL15;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owns context-local VAO names while a display-list batch's buffers remain share-group-owned. */
public final class ContextOwnedVertexArray {

    private final long shareGroupId;
    private final Map<GLContextIdentity, Integer> arrays = new HashMap<>();
    private final AutoCloseable retirementSubscription;
    private boolean deleted;

    public ContextOwnedVertexArray(long shareGroupId) {
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Share-group id must be positive: " + shareGroupId);
        }
        this.shareGroupId = shareGroupId;
        final WeakReference<ContextOwnedVertexArray> owner = new WeakReference<>(this);
        retirementSubscription = GLContextRegistryProvider.addRetirementListener(identity -> {
            final ContextOwnedVertexArray liveOwner = owner.get();
            if (liveOwner != null) {
                liveOwner.retire(identity);
            }
        });
    }

    public synchronized int getOrCreate(GLContextIdentity identity, Runnable configure) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(configure, "configure");
        if (deleted) {
            throw new IllegalStateException("Cannot create a VAO for a deleted display-list batch");
        }
        if (identity.shareGroupId() != shareGroupId) {
            throw new IllegalArgumentException(
                "Display-list buffers belong to share group " + shareGroupId
                    + " but current context belongs to " + identity.shareGroupId());
        }
        if (!identity.equals(GLStateManager.currentContextIdentity())) {
            throw new IllegalStateException("VAO creation identity does not match the current GL context");
        }
        final Integer existing = arrays.get(identity);
        if (existing != null) {
            return existing;
        }

        final int previousVAO = GLStateManager.getBoundVAO();
        final int previousVBO = GLStateManager.getBoundVBO();
        final int previousEBO = GLStateManager.getBoundEBO();
        final int created = GLStateManager.glGenVertexArrays();
        boolean configured = false;
        try {
            GLStateManager.glBindVertexArray(created);
            configure.run();
            configured = true;
        } finally {
            GLStateManager.glBindVertexArray(previousVAO);
            GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousEBO);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousVBO);
            if (!configured) {
                GLStateManager.glDeleteVertexArrays(created);
            }
        }
        arrays.put(identity, created);
        return created;
    }

    public synchronized void delete() {
        if (deleted) {
            return;
        }
        deleted = true;
        for (Map.Entry<GLContextIdentity, Integer> entry : arrays.entrySet()) {
            GLStateManager.deleteVertexArrayForContext(entry.getKey(), entry.getValue());
        }
        arrays.clear();
        closeRetirementSubscription();
    }

    private synchronized void retire(GLContextIdentity identity) {
        arrays.remove(identity);
    }

    private void closeRetirementSubscription() {
        try {
            retirementSubscription.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to unsubscribe a display-list VAO owner", exception);
        }
    }
}
