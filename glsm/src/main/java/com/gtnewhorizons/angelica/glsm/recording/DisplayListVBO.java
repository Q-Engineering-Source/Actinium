package com.gtnewhorizons.angelica.glsm.recording;

import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.streaming.LineQuads;
import org.lwjgl.opengl.GL11;

/**
 * A class that stores multiple vertex formats & their corresponding buffers.
 */
public final class DisplayListVBO {

    private final SubVBO[] vbos;

    DisplayListVBO(SubVBO[] vbos) {
        this.vbos = vbos;
    }

    public void delete() {
        for (SubVBO vbo : vbos) {
            vbo.delete();
        }
    }

    public SubVBO[] getVBOs() {
        return vbos;
    }

    public void render(int index) {
        vbos[index].render();
    }

    public static final class SubVBO {

        private final IVertexArrayObject vao;
        private final int drawMode;
        private final int start;
        private final int count;
        private IVertexArrayObject lineQuads;
        private int lineQuadStart;
        private int lineQuadCount;
        private int lineQuadFlags;

        void setLineQuads(IVertexArrayObject quads, int start, int count, int flags) {
            this.lineQuads = quads;
            this.lineQuadStart = start;
            this.lineQuadCount = count;
            this.lineQuadFlags = flags;
        }

        public SubVBO(IVertexArrayObject vao, int drawMode, int start, int count) {
            this.vao = vao;
            this.drawMode = drawMode;
            this.start = start;
            this.count = count;
        }

        public void delete() {
            vao.delete();
            if (lineQuads != null) lineQuads.delete();
        }

        public void render() {
            if (vao == null) return;
            // The line width is only known at playback, so a line draw keeps its own range and gains quads to widen
            if (lineQuads != null && GLSMDraws.ffpWidensLineQuads()) {
                renderLineQuads();
                return;
            }
            vao.bind();
            vao.draw(drawMode, start, count);
            vao.unbind();
        }

        private void renderLineQuads() {
            lineQuads.bind();
            final boolean culled = LineQuads.beginDraw(lineQuadFlags);
            try {
                lineQuads.draw(GL11.GL_TRIANGLES, lineQuadStart, lineQuadCount);
            } finally {
                LineQuads.endDraw(culled);
            }
            lineQuads.unbind();
        }

        public IVertexArrayObject getVAO() {
            return vao;
        }
    }
}
