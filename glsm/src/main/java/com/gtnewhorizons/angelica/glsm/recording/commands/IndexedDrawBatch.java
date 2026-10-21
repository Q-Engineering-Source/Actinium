package com.gtnewhorizons.angelica.glsm.recording.commands;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.recording.ContextOwnedVertexArray;
import org.lwjgl.opengl.GL15;

/**
 * Share-group buffers and immutable attribute layout backing one compiled-list index group.
 * Its context-local VAOs are created only when that list first renders in each context.
 */
public final class IndexedDrawBatch {
    private final long shareGroupId;
    private final AttribLayoutKey layout;
    private final ContextOwnedVertexArray contextVaos;
    private int sharedVBO;
    private int sharedEBO;
    private boolean deleted;

    public IndexedDrawBatch(long shareGroupId, AttribLayoutKey layout, int sharedVBO, int sharedEBO) {
        this.shareGroupId = shareGroupId;
        this.layout = layout;
        this.sharedVBO = sharedVBO;
        this.sharedEBO = sharedEBO;
        this.contextVaos = new ContextOwnedVertexArray(shareGroupId);
    }

    public int getSharedVBO() { return sharedVBO; }
    public int getSharedEBO() { return sharedEBO; }

    public synchronized int getVertexArray(GLContextIdentity identity) {
        if (deleted) {
            throw new IllegalStateException("Cannot render a deleted indexed display-list batch");
        }
        if (identity.shareGroupId() != shareGroupId) {
            throw new IllegalArgumentException(
                "Indexed display-list buffers belong to share group " + shareGroupId
                    + " but current context belongs to " + identity.shareGroupId());
        }
        return contextVaos.getOrCreate(identity, () -> {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, sharedVBO);
            final int stride = layout.stride();
            for (int i = 0; i < layout.locations.length; i++) {
                GLStateManager.glVertexAttribPointer(
                    layout.locations[i], layout.sizes[i], layout.types[i], layout.normalized[i], stride, layout.offset(i));
                GLStateManager.glEnableVertexAttribArray(layout.locations[i]);
            }
            GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, sharedEBO);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        });
    }

    public synchronized void delete() {
        if (deleted) {
            return;
        }
        deleted = true;
        contextVaos.delete();
        GLStateManager.deleteBufferForShareGroup(shareGroupId, sharedVBO);
        GLStateManager.deleteBufferForShareGroup(shareGroupId, sharedEBO);
        sharedVBO = sharedEBO = 0;
    }

    @Override
    public String toString() {
        return "IndexedDrawBatch(shareGroup=" + shareGroupId + ", vbo=" + sharedVBO + ", ebo=" + sharedEBO + ")";
    }
}
