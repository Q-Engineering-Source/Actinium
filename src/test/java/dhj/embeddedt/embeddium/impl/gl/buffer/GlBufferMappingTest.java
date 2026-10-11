package dhj.embeddedt.embeddium.impl.gl.buffer;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlBufferMappingTest {
    @Test
    void wrappedTransfersRespectTheSourcePositionAndLeaveBothBuffersUnchanged() {
        ByteBuffer source = ByteBuffer.allocateDirect(16);
        ByteBuffer destination = ByteBuffer.allocateDirect(8);
        for (int i = 0; i < source.capacity(); i++) {
            source.put(i, (byte) (20 + i));
        }
        source.position(3).limit(11);
        GlBufferMapping mapping = new GlBufferMapping(null, destination);
        mapping.write(source, 0, 6, 2);
        mapping.write(source, 2, 0, 6);
        assertEquals(23, destination.get(6));
        assertEquals(24, destination.get(7));
        for (int i = 0; i < 6; i++) {
            assertEquals(25 + i, destination.get(i));
        }
        assertEquals(3, source.position());
        assertEquals(11, source.limit());
        assertEquals(0, destination.position());
        assertEquals(8, destination.limit());
    }

    @Test
    void ordinaryWriteUsesTheRemainingSourceRange() {
        ByteBuffer source = ByteBuffer.allocateDirect(8);
        source.put(2, (byte) 81).put(3, (byte) 82).position(2).limit(4);
        ByteBuffer destination = ByteBuffer.allocateDirect(8);
        new GlBufferMapping(null, destination).write(source, 4);
        assertEquals(81, destination.get(4));
        assertEquals(82, destination.get(5));
        assertEquals(2, source.position());
    }

    @Test
    void invalidRangesFailBeforeNativeMemoryIsAccessed() {
        ByteBuffer source = ByteBuffer.allocateDirect(4);
        ByteBuffer destination = ByteBuffer.allocateDirect(4);
        GlBufferMapping mapping = new GlBufferMapping(null, destination);
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.write(source, 1, 0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.write(source, 0, 1, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.write(source, -1, 0, 1));
    }
}
