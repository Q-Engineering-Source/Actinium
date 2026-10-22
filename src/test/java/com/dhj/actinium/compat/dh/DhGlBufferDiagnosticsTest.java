package com.dhj.actinium.compat.dh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DhGlBufferDiagnosticsTest {
    @Test
    void createdBufferWithoutAnUploadIsStillCountedWithZeroCapacity() {
        DhGlBufferDiagnostics.BufferLedger ledger = new DhGlBufferDiagnostics.BufferLedger();
        ledger.created(17, 1);

        assertEquals(new DhGlBufferDiagnostics.BufferLedgerSnapshot(1, 1, 0L, 0L, 1L, 0L, 0L, 1),
                ledger.snapshot());
    }

    @Test
    void capacityUpdatesCountOnlyActualChangesIncludingSubDataGrowth() {
        DhGlBufferDiagnostics.BufferLedger ledger = new DhGlBufferDiagnostics.BufferLedger();
        ledger.created(31, 1);

        ledger.capacityReported(31, 4096, 1);
        ledger.capacityReported(31, 4096, 1); // uploadSubData with existing capacity
        assertEquals(new DhGlBufferDiagnostics.BufferLedgerSnapshot(1, 1, 4096L, 4096L, 1L, 1L, 0L, 1),
                ledger.snapshot());

        ledger.capacityReported(31, 8192, 1); // uploadSubData after DH grows its store
        assertEquals(new DhGlBufferDiagnostics.BufferLedgerSnapshot(1, 1, 8192L, 8192L, 1L, 2L, 0L, 1),
                ledger.snapshot());
    }

    @Test
    void asynchronousDestroyKeepsBytesLiveUntilTheGlDeleteCallbackReturns() {
        DhGlBufferDiagnostics.BufferLedger ledger = new DhGlBufferDiagnostics.BufferLedger();
        ledger.created(42, 1);
        ledger.capacityReported(42, 16384, 1);

        // destroyAsync has queued deletion but has not called destroyBufferIdNow yet.
        assertEquals(16384L, ledger.snapshot().liveCapacityBytesReported());
        assertEquals(1, ledger.snapshot().liveBufferCount());

        ledger.deleted(42, 0);
        assertEquals(new DhGlBufferDiagnostics.BufferLedgerSnapshot(0, 1, 0L, 16384L, 1L, 1L, 1L, 0),
                ledger.snapshot());
    }

    @Test
    void reusedGlIdStartsASeparateLiveBufferLifetime() {
        DhGlBufferDiagnostics.BufferLedger ledger = new DhGlBufferDiagnostics.BufferLedger();
        ledger.created(9, 1);
        ledger.capacityReported(9, 5000, 1);
        ledger.deleted(9, 0);

        ledger.created(9, 1);
        assertEquals(new DhGlBufferDiagnostics.BufferLedgerSnapshot(1, 1, 0L, 5000L, 2L, 1L, 1L, 1),
                ledger.snapshot());

        ledger.capacityReported(9, 2048, 1);
        assertEquals(new DhGlBufferDiagnostics.BufferLedgerSnapshot(1, 1, 2048L, 5000L, 2L, 2L, 1L, 1),
                ledger.snapshot());
    }
}
