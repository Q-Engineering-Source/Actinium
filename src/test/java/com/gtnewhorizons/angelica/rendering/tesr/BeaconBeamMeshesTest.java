package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizons.angelica.api.tesr.TesrMaterial;
import com.gtnewhorizons.angelica.api.tesr.TesrMeshSink;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the beacon beam geometry for the segment heights 1.12.2 actually produces.
 *
 * <p>A segment reaches from the beacon to the top of the world, so its height is {@code 256 - y} and
 * at most 255; a mesh built for a fixed 256 would be too tall and its texture V extent would not
 * match the segment. The build limit means several segments can stack, each with its own height and
 * colour, so the geometry is built per segment.</p>
 */
class BeaconBeamMeshesTest {

    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};
    private static final int VERTEX_BYTES = 20;

    /** Records the vertices a bucket receives into a direct buffer the test can read back. */
    private static final class RecordingSink implements TesrMeshSink {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
        final List<TesrMaterial> materials = new ArrayList<>();
        DirectTessellator last;

        @Override
        public DirectTessellator angelica$bucket(VertexFormat format, ResourceLocation texture, TesrMaterial material) {
            materials.add(material);
            last = new DirectTessellator(buffer);
            return last;
        }

        int vertexCount() {
            return last.getVertexCount();
        }

        float get(int vertex, int component) {
            return buffer.getFloat(vertex * VERTEX_BYTES + component * 4);
        }
    }

    @Test
    void buildsTheInnerBeamForAnySegmentHeight() {
        final RecordingSink sink = new RecordingSink();

        BeaconBeamMeshes.buildInner(sink, 100, WHITE);

        assertEquals(16, sink.vertexCount(), "four side quads");
        assertEquals(100.0F, maxY(sink), 0.000001F, "the tube spans the segment height");
        assertEquals((float) BeaconBeamMeshes.innerVTop(100), maxV(sink), 0.000001F);
        assertEquals(250.0F, maxV(sink), 0.000001F, "vanilla scales the inner V by 0.5 / beamRadius");
        assertEquals(1.0F, alpha(sink), 0.000001F, "the inner beam is opaque");
        assertEquals((float) BeaconBeamMeshes.BEAM_RADIUS, maxRadius(sink), 0.00001F,
            "the tube corners sit on the beam radius");
    }

    @Test
    void buildsTheGlowBoxForAnySegmentHeight() {
        final RecordingSink sink = new RecordingSink();

        BeaconBeamMeshes.buildOuter(sink, 100, WHITE);

        assertEquals(16, sink.vertexCount(), "four side quads");
        assertEquals(100.0F, maxY(sink), 0.000001F);
        assertEquals(100.0F, maxV(sink), 0.000001F, "the glow V span is one texture repeat per block");
        assertEquals(BeaconBeamMeshes.GLOW_ALPHA, alpha(sink), 0.000001F);
        assertEquals((float) BeaconBeamMeshes.GLOW_RADIUS, maxAbsXz(sink), 0.00001F);
    }

    @Test
    void aShorterSegmentIsNotTheWorldHeightOne() {
        final RecordingSink tall = new RecordingSink();
        BeaconBeamMeshes.buildInner(tall, 255, WHITE);
        final float tallV = maxV(tall);

        final RecordingSink shortSegment = new RecordingSink();
        BeaconBeamMeshes.buildInner(shortSegment, 40, WHITE);

        assertEquals(40.0F, maxY(shortSegment), 0.000001F);
        assertNotEquals(tallV, maxV(shortSegment), "the V extent follows the segment height");
        assertEquals(0.0F, minY(shortSegment), 0.000001F);
    }

    private static float maxY(RecordingSink sink) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < sink.vertexCount(); i++) {
            max = Math.max(max, sink.get(i, 1));
        }
        return max;
    }

    private static float minY(RecordingSink sink) {
        float min = Float.POSITIVE_INFINITY;
        for (int i = 0; i < sink.vertexCount(); i++) {
            min = Math.min(min, sink.get(i, 1));
        }
        return min;
    }

    private static float maxV(RecordingSink sink) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < sink.vertexCount(); i++) {
            max = Math.max(max, sink.get(i, 4));
        }
        return max;
    }

    private static float maxAbsXz(RecordingSink sink) {
        float max = 0.0F;
        for (int i = 0; i < sink.vertexCount(); i++) {
            max = Math.max(max, Math.abs(sink.get(i, 0)));
            assertTrue(Math.abs(sink.get(i, 2)) <= BeaconBeamMeshes.GLOW_RADIUS + 0.00001F);
        }
        return max;
    }

    private static float maxRadius(RecordingSink sink) {
        float max = 0.0F;
        for (int i = 0; i < sink.vertexCount(); i++) {
            final float x = sink.get(i, 0);
            final float z = sink.get(i, 2);
            max = Math.max(max, (float) Math.sqrt(x * x + z * z));
        }
        return max;
    }

    private static float alpha(RecordingSink sink) {
        return sink.materials.get(0).colorAlpha();
    }
}
