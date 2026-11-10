package com.dhj.actinium.render.terrain.deferred;

import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the raw vertex capture a deferred mesh carries between the main-thread capture and the
 * worker-side finish: entries keep their material, layer and quad contexts, the bytes are copied out
 * of the render buffer so the caller can reuse it, and the native block is recycled or freed
 * explicitly instead of being left to the garbage collector.
 */
class DeferredSectionMeshTest {
    private static final int OVERSIZED_BYTES = 4 * 1024 * 1024;

    private final Material solid = material("solid");
    private final Material translucent = material("translucent");

    @Test
    void captureAppendsEntriesInOrderWithTheirMaterialLayerAndQuadContexts() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        VanillaQuadContext firstContext = context(1);
        VanillaQuadContext secondContext = context(2);

        capture.add(this.solid, BlockRenderLayer.SOLID, bytes(1, 2, 3, 4), List.of(firstContext));
        capture.add(this.translucent, BlockRenderLayer.TRANSLUCENT, bytes(9, 8), List.of(firstContext, secondContext));

        assertEquals(2, capture.size());

        DeferredSectionMesh.DeferredCapture.Entry solidEntry = capture.entry(0);
        assertSame(this.solid, solidEntry.material());
        assertEquals(BlockRenderLayer.SOLID, solidEntry.layer());
        assertEquals(0, solidEntry.offset());
        assertEquals(4, solidEntry.length());
        assertEquals(List.of(firstContext), solidEntry.quadContexts());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, read(capture.view(solidEntry)));

        DeferredSectionMesh.DeferredCapture.Entry translucentEntry = capture.entry(1);
        assertSame(this.translucent, translucentEntry.material());
        assertEquals(BlockRenderLayer.TRANSLUCENT, translucentEntry.layer());
        assertEquals(4, translucentEntry.offset());
        assertEquals(2, translucentEntry.length());
        assertEquals(List.of(firstContext, secondContext), translucentEntry.quadContexts());
        assertArrayEquals(new byte[] { 9, 8 }, read(capture.view(translucentEntry)));
    }

    @Test
    void captureCopiesTheBytesOutOfTheRenderBuffer() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        ByteBuffer renderBuffer = bytes(1, 2, 3, 4);

        capture.add(this.solid, BlockRenderLayer.SOLID, renderBuffer, List.of());

        renderBuffer.put(0, (byte) 99);

        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, read(capture.view(capture.entry(0))));
    }

    @Test
    void captureCopiesOnlyTheRemainingBytesOfTheSource() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        ByteBuffer renderBuffer = bytes(1, 2, 3, 4);
        renderBuffer.position(1);

        capture.add(this.solid, BlockRenderLayer.SOLID, renderBuffer, List.of());

        assertEquals(3, capture.entry(0).length());
        assertArrayEquals(new byte[] { 2, 3, 4 }, read(capture.view(capture.entry(0))));
        assertEquals(4, renderBuffer.position());
    }

    @Test
    void clearDropsTheEntriesAndKeepsTheBufferForReuse() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        capture.add(this.solid, BlockRenderLayer.SOLID, bytes(1, 2, 3, 4), List.of());
        int capacity = capture.capacity();
        assertTrue(capacity > 0);

        capture.clear();

        assertEquals(0, capture.size());
        assertEquals(capacity, capture.capacity());

        capture.add(this.translucent, BlockRenderLayer.CUTOUT, bytes(7), List.of());
        assertEquals(1, capture.size());
        assertEquals(0, capture.entry(0).offset());
        assertArrayEquals(new byte[] { 7 }, read(capture.view(capture.entry(0))));
    }

    @Test
    void clearShrinksABufferThatGrewFarBeyondTheRetainedSize() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        capture.add(this.solid, BlockRenderLayer.SOLID, ByteBuffer.allocateDirect(OVERSIZED_BYTES), List.of());
        int grown = capture.capacity();
        assertTrue(grown >= OVERSIZED_BYTES);

        capture.clear();

        assertTrue(capture.capacity() < grown, "cleared capture keeps " + capture.capacity() + " bytes of the " + grown + " it grew to");

        capture.add(this.solid, BlockRenderLayer.SOLID, bytes(5, 6), List.of());
        assertArrayEquals(new byte[] { 5, 6 }, read(capture.view(capture.entry(0))));
    }

    @Test
    void captureRejectsHeapBuffers() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        ByteBuffer heap = ByteBuffer.allocate(4);
        heap.put(new byte[] { 1, 2, 3, 4 });
        heap.flip();

        assertThrows(IllegalArgumentException.class,
                () -> capture.add(this.solid, BlockRenderLayer.SOLID, heap, List.of()));

        assertEquals(0, capture.size());
        assertEquals(0, heap.position(), "the rejected buffer must not be advanced");

        capture.add(this.solid, BlockRenderLayer.SOLID, bytes(7), List.of());
        assertArrayEquals(new byte[] { 7 }, read(capture.view(capture.entry(0))));
    }

    @Test
    void disposeFreesTheNativeBlockAndIsIdempotent() {
        DeferredSectionMesh.DeferredCapture capture = new DeferredSectionMesh.DeferredCapture();
        capture.add(this.solid, BlockRenderLayer.SOLID, bytes(1, 2), List.of());
        assertTrue(capture.capacity() > 0);

        capture.dispose();

        assertEquals(0, capture.capacity());
        assertEquals(0, capture.size());
        capture.dispose();
        assertEquals(0, capture.capacity());
        assertThrows(IllegalStateException.class, () -> capture.add(this.solid, BlockRenderLayer.SOLID, bytes(1), List.of()));
    }

    @Test
    void meshDelegatesObsoleteCaptureAndFinishToItsHandler() {
        RecordingHandler handler = new RecordingHandler();
        DeferredSectionMesh mesh = new DeferredSectionMesh(handler, token(), new ChunkBuildBuffers(null),
                new RecordingDeferredSlice().attachSnapshot(), true, 12L, 34L);
        ChunkBuildContext context = new ChunkBuildContext(null);

        handler.obsolete = true;
        assertTrue(mesh.isObsolete());
        handler.obsolete = false;
        assertEquals(false, mesh.isObsolete());

        assertSame(handler.captureResult, mesh.capture());
        assertEquals(1, handler.captureCalls);
        assertSame(mesh, handler.lastCapturedFor);

        assertSame(handler.output, mesh.finish(context));
        assertEquals(1, handler.finishCalls);
        assertSame(mesh, handler.lastFinishedFor);
        assertSame(context, handler.lastFinishContext);
    }

    private static VanillaQuadContext context(int blockStateId) {
        return new VanillaQuadContext(1, 2, 3, blockStateId, (short) 0, (byte) 15);
    }

    private static CancellationToken token() {
        return new CancellationToken() {
            private boolean cancelled;

            @Override
            public boolean isCancelled() {
                return this.cancelled;
            }

            @Override
            public void setCancelled() {
                this.cancelled = true;
            }
        };
    }

    private static ByteBuffer bytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length);
        for (int value : values) {
            buffer.put((byte) value);
        }
        buffer.flip();
        return buffer;
    }

    private static byte[] read(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private static Material material(String name) {
        TerrainRenderPass pass = TerrainRenderPass.builder()
                .name(name)
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(ChunkMeshFormats.COMPACT)
                .build();
        return new Material(pass, AlphaCutoffParameter.ZERO, true);
    }

    private static final class RecordingHandler implements DeferredSectionMesh.Handler {
        private boolean obsolete;
        private int captureCalls;
        private int finishCalls;
        private DeferredSectionMesh lastCapturedFor;
        private DeferredSectionMesh lastFinishedFor;
        private ChunkBuildContext lastFinishContext;
        private final ChunkJobResult<ChunkBuildOutput> captureResult = new ChunkJobResult.Failure<>(new IllegalStateException("capture failed"));
        private final ChunkBuildOutput output = new ChunkBuildOutput(null, null, Reference2ReferenceMaps.emptyMap(), 0);

        @Override
        public boolean isObsolete() {
            return this.obsolete;
        }

        @Override
        public ChunkJobResult<ChunkBuildOutput> captureDeferred(DeferredSectionMesh mesh) {
            this.captureCalls++;
            this.lastCapturedFor = mesh;
            return this.captureResult;
        }

        @Override
        public void retryLater(DeferredSectionMesh mesh) {
            // Only the scheduler decides when a mesh is retried; this handler never receives one.
        }

        @Override
        public ChunkBuildOutput finishDeferred(DeferredSectionMesh mesh, ChunkBuildContext context) {
            this.finishCalls++;
            this.lastFinishedFor = mesh;
            this.lastFinishContext = context;
            return this.output;
        }
    }
}
