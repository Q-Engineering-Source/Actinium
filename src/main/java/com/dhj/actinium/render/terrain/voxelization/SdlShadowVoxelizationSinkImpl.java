package com.dhj.actinium.render.terrain.voxelization;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

/** Submits every region of a terrain pass through one SDL compute encoder. */
final class SdlShadowVoxelizationSinkImpl implements ShadowVoxelizationSink {
    private static final int VERTEX_BINDING = RwImageStoreExtractor.VG_VBUF_SSBO_BINDING;
    private static final int RANGE_BINDING = RwImageStoreExtractor.VG_RANGES_SSBO_BINDING;

    private final VoxelRangeTable table = new VoxelRangeTable();
    private int rangeBuffer;
    private long rangeCapacity;

    @Override
    public boolean region(RenderRegion region, float offsetX, float offsetY, float offsetZ) {
        RenderRegion.DeviceResources resources = region.getResources();
        if (resources == null) {
            return false;
        }
        this.table.beginRegion(resources.getVertexBuffer().handle(), offsetX, offsetY, offsetZ);
        return true;
    }

    @Override
    public void range(int vertexOffset, int vertexCount) {
        this.table.addRange(vertexOffset, vertexCount);
    }

    @Override
    public void flush() {
        if (this.table.entryCount() == 0) {
            return;
        }
        int previousBuffer = GLSMQueries.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        StorageBinding previousVertexBuffer = StorageBinding.capture(VERTEX_BINDING);
        StorageBinding previousRangeBuffer = StorageBinding.capture(RANGE_BINDING);
        RenderBackend backend = BackendManager.RENDER_BACKEND;
        try {
            this.upload();
            GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VERTEX_BINDING, this.table.vertexBuffer(0));
            long encoder = backend.beginVoxelizationBatch(VERTEX_BINDING);
            if (encoder == 0L) {
                throw new IllegalStateException("SDL refused the shadow terrain voxelization compute encoder");
            }
            try {
                for (int region = 0; region < this.table.regionCount(); region++) {
                    if (region > 0) {
                        GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VERTEX_BINDING,
                            this.table.vertexBuffer(region));
                    }
                    backend.voxelizeRegion(encoder, region > 0,
                        this.table.offsetX(region), this.table.offsetY(region), this.table.offsetZ(region),
                        this.table.rangeBase(region), this.table.rangeCount(region), this.table.vertexTotal(region));
                }
            } finally {
                backend.endVoxelizationBatch(encoder);
            }
            GLSMBuffers.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        } finally {
            previousVertexBuffer.restore(VERTEX_BINDING);
            previousRangeBuffer.restore(RANGE_BINDING);
            GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousBuffer);
        }
    }

    private void upload() {
        ByteBuffer data = this.table.entries();
        if (this.rangeBuffer == 0) {
            this.rangeBuffer = GLSMBuffers.glGenBuffers();
        }
        GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RANGE_BINDING, this.rangeBuffer);
        if (data.remaining() > this.rangeCapacity) {
            long capacity = Math.max(4096, this.rangeCapacity);
            while (capacity < data.remaining()) {
                capacity *= 2;
            }
            this.rangeCapacity = capacity;
            GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, capacity, GL15.GL_STREAM_DRAW);
        }
        GLSMBuffers.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, data);
    }

    @Override
    public void discard() {
        this.table.clear();
    }

    @Override
    public void delete() {
        if (this.rangeBuffer != 0) {
            GLSMBuffers.glDeleteBuffers(this.rangeBuffer);
            this.rangeBuffer = 0;
            this.rangeCapacity = 0;
        }
        this.table.free();
    }

    /** Retains the complete indexed binding while voxelization temporarily borrows its slot. */
    private record StorageBinding(int buffer, long offset, long size) {
        static StorageBinding capture(int index) {
            return new StorageBinding(
                GLSMQueries.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index),
                GLSMQueries.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, index),
                GLSMQueries.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, index));
        }

        void restore(int index) {
            if (this.size > 0) {
                GLSMBuffers.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, index, this.buffer, this.offset, this.size);
            } else {
                GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, this.buffer);
            }
        }
    }
}
