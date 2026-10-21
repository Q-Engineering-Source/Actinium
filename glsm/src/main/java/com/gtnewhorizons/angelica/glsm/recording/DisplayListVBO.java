package com.gtnewhorizons.angelica.glsm.recording;

import com.gtnewhorizon.gtnhlib.client.opengl.GLCaps;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/** Stores display-list draw ranges and owns their share-group buffers and per-context VAOs. */
public final class DisplayListVBO {

    private final SubVBO[] vbos;
    private final int[] ownedBuffers;
    private final long shareGroupId;
    private boolean deleted;

    DisplayListVBO(SubVBO[] vbos, int[] ownedBuffers, long shareGroupId) {
        this.vbos = vbos;
        this.ownedBuffers = ownedBuffers;
        this.shareGroupId = shareGroupId;
    }

    public synchronized void delete() {
        if (deleted) {
            return;
        }
        deleted = true;
        for (SubVBO vbo : vbos) {
            vbo.delete();
        }
        for (int buffer : ownedBuffers) {
            GLStateManager.deleteBufferForShareGroup(shareGroupId, buffer);
        }
    }

    public SubVBO[] getVBOs() {
        return vbos;
    }

    public void render(int index) {
        if (deleted) {
            throw new IllegalStateException("Cannot render a deleted display-list VBO");
        }
        vbos[index].render();
    }

    /** One draw range over a share-group VBO, with an EBO when quads were triangulated. */
    public static final class SubVBO {

        private final int vbo;
        private final int ebo;
        private final VertexFormat format;
        private final int drawMode;
        private final int start;
        private final int count;
        private final ContextOwnedVertexArray contextVaos;
        private boolean deleted;

        public SubVBO(
            int vbo,
            int ebo,
            VertexFormat format,
            int drawMode,
            int start,
            int count,
            long shareGroupId
        ) {
            this.vbo = vbo;
            this.ebo = ebo;
            this.format = format;
            this.drawMode = drawMode;
            this.start = start;
            this.count = count;
            this.contextVaos = new ContextOwnedVertexArray(shareGroupId);
        }

        public synchronized void delete() {
            if (deleted) {
                return;
            }
            deleted = true;
            contextVaos.delete();
        }

        public synchronized void render() {
            if (deleted) {
                throw new IllegalStateException("Cannot render a deleted display-list draw range");
            }
            if (GLCaps.VAO == null) {
                drawWithoutVertexArray();
                return;
            }

            final GLContextIdentity identity = GLStateManager.currentContextIdentity();
            if (identity == null) {
                throw new IllegalStateException("Display-list draw requires a registered current context");
            }
            final int vao = contextVaos.getOrCreate(identity, () -> configureVertexArray());
            GLStateManager.glBindVertexArray(vao);
            if (ebo == 0) {
                GLStateManager.glDrawArrays(drawMode, start, count);
            } else {
                GLStateManager.glDrawElements(drawMode, count, GL11.GL_UNSIGNED_SHORT, 0L);
            }
            GLStateManager.glBindVertexArray(0);
        }

        private void configureVertexArray() {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            format.setupBufferState(0L);
            if (ebo != 0) {
                GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            }
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }

        private void drawWithoutVertexArray() {
            final int previousVbo = GLStateManager.getBoundVBO();
            final int previousEbo = GLStateManager.getBoundEBO();
            try {
                GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                format.setupBufferState(0L);
                if (ebo != 0) {
                    GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
                    GLStateManager.glDrawElements(drawMode, count, GL11.GL_UNSIGNED_SHORT, 0L);
                } else {
                    GLStateManager.glDrawArrays(drawMode, start, count);
                }
            } finally {
                format.clearBufferState();
                GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousEbo);
                GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousVbo);
            }
        }

        public int getVertexBuffer() {
            return vbo;
        }

        public int getIndexBuffer() {
            return ebo;
        }
    }
}
