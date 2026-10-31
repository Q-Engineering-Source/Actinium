package com.gtnewhorizons.angelica.glsm.debug;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.glsm.QuadConverter;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GLSMPerfDebugTest {
    /**
     * Larger than any default sampling cadence used by the perf debug stages: within one batch
     * the default cadence must skip events while an always-sample stage must time all of them.
     */
    private static final int EVENTS_PER_BATCH = 1024;
    private static final List<GLSMPerfDebug.Stage> SHADOW_STAGES = List.of(
        GLSMPerfDebug.Stage.SHADOW_RENDER,
        GLSMPerfDebug.Stage.SHADOW_SETUP_TERRAIN,
        GLSMPerfDebug.Stage.SHADOW_OPAQUE_TERRAIN,
        GLSMPerfDebug.Stage.SHADOW_ENTITIES,
        GLSMPerfDebug.Stage.SHADOW_TRANSLUCENT_TERRAIN,
        GLSMPerfDebug.Stage.SHADOW_COMPOSITE
    );
    private static final List<GLSMPerfDebug.Stage> BUFFERBUILDER_DRAW_PHASES = List.of(
        GLSMPerfDebug.Stage.BUFFERBUILDER_INITIAL_VAO_BIND,
        GLSMPerfDebug.Stage.BUFFERBUILDER_PREPARE_DRAW,
        GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS,
        GLSMPerfDebug.Stage.BUFFERBUILDER_RESTORE_BINDINGS
    );

    @AfterEach
    void disableConfiguredDebug() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setExtraStatsSupplier(null);
    }

    @Test
    void explicitPropertyValueOverridesConfiguredValue() {
        assertTrue(GLSMPerfDebug.resolveEnabled("true", false));
        assertFalse(GLSMPerfDebug.resolveEnabled("false", true));
        assertTrue(GLSMPerfDebug.resolveEnabled(null, true));
        assertFalse(GLSMPerfDebug.resolveEnabled(null, false));
    }

    @Test
    void appliesConfigurationChangesAtRuntime() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        assertFalse(GLSMPerfDebug.isEnabled());

        GLSMPerfDebugHooks.setConfiguredEnabled(true);
        assertTrue(GLSMPerfDebug.isEnabled());

        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        assertFalse(GLSMPerfDebug.isEnabled());
    }

    @Test
    void closesEmptyTessellatorDrawSample() {
        primeNextSample(GLSMPerfDebug.Stage.STREAM_DRAW);
        EmptyTessellatorData tessellator = new EmptyTessellatorData();

        assertEquals(28, TessellatorStreamingDrawer.draw(tessellator));

        assertTrue(tessellator.reset);
        assertFalse(tessellator.drawing);
        assertEquals(1, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.STREAM_DRAW));
    }

    @Test
    void closesMissingFormatDirectDrawSample() {
        primeNextSample(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT);
        DirectTessellator tessellator = new DirectTessellator(ByteBuffer.allocateDirect(1));

        TessellatorStreamingDrawer.drawDirect(tessellator);

        assertEquals(1, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT));
    }

    @Test
    void closesEmptyDirectDrawSample() {
        primeNextSample(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT);
        DirectTessellator tessellator = new DirectTessellator(ByteBuffer.allocateDirect(1));
        tessellator.setVertexFormat(DefaultVertexFormat.ALL_FORMATS[0]);

        TessellatorStreamingDrawer.drawDirect(tessellator);

        assertEquals(1, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT));
    }

    @Test
    void closesEmptyQuadElementsSample() {
        primeNextSample(GLSMPerfDebug.Stage.QUAD_ELEMENTS);

        QuadConverter.drawQuadElementsAsTriangles(0, GL11.GL_UNSIGNED_INT, 0L);

        assertEquals(1, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.QUAD_ELEMENTS));
    }

    @Test
    void accumulatesPersistentFenceReclaimAndQueueMetrics() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);

        GLSMPerfDebug.recordFenceReclaim(128);
        GLSMPerfDebug.recordFenceReclaim(384);
        GLSMPerfDebug.recordFenceQueueDepth(2);
        GLSMPerfDebug.recordFenceQueueDepth(5);
        GLSMPerfDebug.recordFenceQueueDepth(3);

        assertEquals(2, GLSMPerfDebug.getFenceReclaimedRegions());
        assertEquals(512L, GLSMPerfDebug.getFenceReclaimedBytes());
        assertEquals(5, GLSMPerfDebug.getFenceQueuePeak());
    }

    @Test
    void samplesEveryLowFrequencyFenceEvent() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);

        long first = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE, first);
        long second = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE, second);

        assertEquals(2, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.STREAM_FENCE_CREATE));
    }

    @Test
    void concatenatesMultipleStatsProvidersInRegistrationOrder() {
        GLSMPerfDebugHooks.addStatsProvider(() -> "alpha=1");
        GLSMPerfDebugHooks.addStatsProvider(() -> "beta=2");

        assertEquals("alpha=1 beta=2", GLSMPerfDebugHooks.getExtraStats());
    }

    @Test
    void skipsEmptyProviderOutputWhenConcatenating() {
        GLSMPerfDebugHooks.addStatsProvider(() -> "");
        GLSMPerfDebugHooks.addStatsProvider(() -> "beta=2");
        GLSMPerfDebugHooks.addStatsProvider(() -> "");

        assertEquals("beta=2", GLSMPerfDebugHooks.getExtraStats());
    }

    @Test
    void removedStatsProviderNoLongerContributesToReport() {
        Supplier<String> removed = () -> "gone=1";
        GLSMPerfDebugHooks.addStatsProvider(removed);
        GLSMPerfDebugHooks.addStatsProvider(() -> "kept=2");

        assertTrue(GLSMPerfDebugHooks.removeStatsProvider(removed));

        assertEquals("kept=2", GLSMPerfDebugHooks.getExtraStats());
    }

    @Test
    void setExtraStatsSupplierReplacesRegisteredProviders() {
        GLSMPerfDebugHooks.addStatsProvider(() -> "first=1");
        GLSMPerfDebugHooks.addStatsProvider(() -> "second=2");

        GLSMPerfDebugHooks.setExtraStatsSupplier(() -> "single=3");

        assertEquals("single=3", GLSMPerfDebugHooks.getExtraStats());
    }

    @Test
    void samplesEveryChunkUpdateChunksEvent() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);

        long first = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.CHUNK_UPDATE_CHUNKS);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.CHUNK_UPDATE_CHUNKS, first);
        long second = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.CHUNK_UPDATE_CHUNKS);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.CHUNK_UPDATE_CHUNKS, second);

        assertEquals(2, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.CHUNK_UPDATE_CHUNKS));
    }

    @Test
    void samplesEveryChunkOcclusionSearchEvent() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);

        long first = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.CHUNK_OCCLUSION_SEARCH);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.CHUNK_OCCLUSION_SEARCH, first);
        long second = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.CHUNK_OCCLUSION_SEARCH);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.CHUNK_OCCLUSION_SEARCH, second);

        assertEquals(2, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.CHUNK_OCCLUSION_SEARCH));
    }

    @Test
    void samplesEveryShadowStageEventWithoutCadenceSkipping() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);

        for (GLSMPerfDebug.Stage stage : SHADOW_STAGES) {
            for (int i = 0; i < EVENTS_PER_BATCH; i++) {
                final long start = GLSMPerfDebug.begin(stage);
                assertTrue(start != 0L, stage.name() + " must be timed on every event");
                GLSMPerfDebug.end(stage, start);
            }

            assertEquals(EVENTS_PER_BATCH, GLSMPerfDebug.getSampledCount(stage), stage.name() + " sample count");
        }
    }

    @Test
    void keepsBufferBuilderDrawPhasesAtDefaultCadence() {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);

        for (GLSMPerfDebug.Stage stage : BUFFERBUILDER_DRAW_PHASES) {
            int timed = 0;
            for (int i = 0; i < EVENTS_PER_BATCH; i++) {
                final long start = GLSMPerfDebug.begin(stage);
                if (start != 0L) {
                    timed++;
                    GLSMPerfDebug.end(stage, start);
                }
            }

            assertTrue(timed > 0, stage.name() + " must be timed at least once per batch");
            assertTrue(timed < EVENTS_PER_BATCH, stage.name() + " must keep skipping events at the default cadence");
            assertEquals(timed, GLSMPerfDebug.getSampledCount(stage), stage.name() + " sample count");
        }
    }

    @Test
    void samplesChunkUploadAtDefaultCadence() {
        primeNextSample(GLSMPerfDebug.Stage.CHUNK_UPLOAD);

        long start = GLSMPerfDebug.begin(GLSMPerfDebug.Stage.CHUNK_UPLOAD);
        assertTrue(start != 0L);
        GLSMPerfDebug.end(GLSMPerfDebug.Stage.CHUNK_UPLOAD, start);

        assertEquals(1, GLSMPerfDebug.getSampledCount(GLSMPerfDebug.Stage.CHUNK_UPLOAD));
    }

    private static void primeNextSample(GLSMPerfDebug.Stage stage) {
        GLSMPerfDebugHooks.setConfiguredEnabled(false);
        GLSMPerfDebugHooks.setConfiguredEnabled(true);
        for (int i = 0; i < 255; i++) {
            assertEquals(0L, GLSMPerfDebug.begin(stage));
        }
    }

    private static final class EmptyTessellatorData implements ITessellatorData {
        private boolean drawing = true;
        private boolean reset;

        @Override
        public boolean isDrawing() {
            return this.drawing;
        }

        @Override
        public void setDrawing(boolean drawing) {
            this.drawing = drawing;
        }

        @Override
        public int getVertexCount() {
            return 0;
        }

        @Override
        public int[] getRawBuffer() {
            return new int[0];
        }

        @Override
        public int getRawBufferIndex() {
            return 7;
        }

        @Override
        public int getRawBufferSize() {
            return 0;
        }

        @Override
        public void setRawBufferSize(int size) {
            throw new AssertionError("Empty draw must not resize its raw buffer");
        }

        @Override
        public void setRawBuffer(int[] buffer) {
            throw new AssertionError("Empty draw must not replace its raw buffer");
        }

        @Override
        public int getDrawMode() {
            return GL11.GL_QUADS;
        }

        @Override
        public boolean hasTexture() {
            return false;
        }

        @Override
        public boolean hasColor() {
            return false;
        }

        @Override
        public boolean hasNormals() {
            return false;
        }

        @Override
        public boolean hasBrightness() {
            return false;
        }

        @Override
        public void angelica$reset() {
            this.reset = true;
        }
    }
}
