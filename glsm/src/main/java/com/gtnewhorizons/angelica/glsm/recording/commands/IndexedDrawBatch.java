package com.gtnewhorizons.angelica.glsm.recording.commands;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

/**
 * Shared GPU triple (VAO + VBO + EBO) backing every baked indexed draw of one
 * attrib-layout group in a compiled display list. Owned by the list; freed on delete.
 */
public final class IndexedDrawBatch {
    private int sharedVAO;
    private int sharedVBO;
    private int sharedEBO;
    private boolean deleted;

    public IndexedDrawBatch(int sharedVAO, int sharedVBO, int sharedEBO) {
        this.sharedVAO = sharedVAO;
        this.sharedVBO = sharedVBO;
        this.sharedEBO = sharedEBO;
    }

    public int getSharedVAO() { return sharedVAO; }
    public int getSharedVBO() { return sharedVBO; }
    public int getSharedEBO() { return sharedEBO; }

    public void delete() {
        if (deleted) return;
        deleted = true;
        if (sharedVAO != 0) GLStateManager.glDeleteVertexArrays(sharedVAO);
        if (sharedVBO != 0) GLStateManager.glDeleteBuffers(sharedVBO);
        if (sharedEBO != 0) GLStateManager.glDeleteBuffers(sharedEBO);
        sharedVAO = sharedVBO = sharedEBO = 0;
    }

    @Override
    public String toString() {
        return "IndexedDrawBatch(vao=" + sharedVAO + ", vbo=" + sharedVBO + ", ebo=" + sharedEBO + ")";
    }
}
