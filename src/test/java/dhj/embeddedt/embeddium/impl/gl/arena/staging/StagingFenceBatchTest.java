package dhj.embeddedt.embeddium.impl.gl.arena.staging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StagingFenceBatchTest {
    @Test
    void regionAndIndexUploadsShareOneFenceWithoutLosingReservedBytes() {
        StagingFenceBatch batch = new StagingFenceBatchImpl();
        batch.begin();
        for (int i = 0; i < 32; i++) {
            batch.addTransferredBytes(4096);
            assertEquals(0, batch.takeFenceBytes());
            batch.addTransferredBytes(512);
            assertEquals(0, batch.takeFenceBytes());
        }
        batch.end();
        assertEquals(32 * (4096 + 512), batch.takeFenceBytes());
        assertEquals(0, batch.takeFenceBytes());
    }

    @Test
    void uploadsOutsideABatchKeepImmediateFenceSemantics() {
        StagingFenceBatch batch = new StagingFenceBatchImpl();
        batch.addTransferredBytes(64);
        assertEquals(64, batch.takeFenceBytes());
        batch.addTransferredBytes(96);
        assertEquals(96, batch.takeFenceBytes());
    }

    @Test
    void nestedAndEmptyBatchesDoNotCreateEarlyOrEmptyFences() {
        StagingFenceBatch batch = new StagingFenceBatchImpl();
        batch.begin();
        batch.begin();
        batch.addTransferredBytes(256);
        batch.end();
        assertEquals(0, batch.takeFenceBytes());
        batch.end();
        assertEquals(256, batch.takeFenceBytes());
        batch.begin();
        batch.end();
        assertEquals(0, batch.takeFenceBytes());
    }

    @Test
    void exceptionCleanupStillFencesCopiesAlreadyIssued() {
        StagingFenceBatch batch = new StagingFenceBatchImpl();
        batch.begin();
        try {
            assertThrows(IllegalStateException.class, () -> {
                batch.addTransferredBytes(100);
                throw new IllegalStateException("later upload failed");
            });
        } finally {
            batch.end();
        }
        assertEquals(100, batch.takeFenceBytes());
    }
}
