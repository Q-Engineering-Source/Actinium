package com.dhj.actinium.compat.dh;

import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebugHooks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Tracks Distant Horizons GLBuffer identities and the byte capacity DH reports for each one.
 *
 * <p>This observer records logical buffer lifecycle state only. It does not query OpenGL, alter
 * uploads, or claim that the driver's physical residency equals the reported capacity.</p>
 */
public final class DhGlBufferDiagnostics {
    private static final BufferLedger LEDGER = new BufferLedger();
    private static final AtomicBoolean PROVIDER_REGISTERED = new AtomicBoolean();
    private static final Supplier<String> STATS_PROVIDER = DhGlBufferDiagnostics::stats;

    private DhGlBufferDiagnostics() {
    }

    /** Records an id after DH creates it and exposes DH's updated global buffer count. */
    public static void created(int id, int dhReportedBufferCount) {
        LEDGER.created(id, dhReportedBufferCount);
        if (id == 0) return;
        registerStatsProvider();
    }

    /**
     * Records DH's current size after a storage upload. Sub-data uploads report the existing
     * capacity through DH's {@code size} field and therefore do not imply a reallocation.
     */
    public static void capacityReported(int id, int capacityBytes, int dhReportedBufferCount) {
        LEDGER.capacityReported(id, capacityBytes, dhReportedBufferCount);
        if (id == 0 || capacityBytes < 0) return;
        registerStatsProvider();
    }

    /** Removes an id only after DH's render-thread GL deletion has returned. */
    public static void deleted(int id, int dhReportedBufferCount) {
        LEDGER.deleted(id, dhReportedBufferCount);
        if (id == 0) return;
        registerStatsProvider();
    }

    private static void registerStatsProvider() {
        if (PROVIDER_REGISTERED.compareAndSet(false, true)) {
            GLSMPerfDebugHooks.addStatsProvider(STATS_PROVIDER);
        }
    }

    private static String stats() {
        if (!GLSMPerfDebug.isEnabled()) {
            return "";
        }

        BufferLedgerSnapshot snapshot = LEDGER.snapshot();
        return "[ActiniumMemory] dh.glBufferLiveCount=" + snapshot.liveBufferCount()
                + " dh.glBufferCapacityBytesReported=" + snapshot.liveCapacityBytesReported()
                + " dh.glBufferCapacityBytesReportedPeak=" + snapshot.peakCapacityBytesReported()
                + " dh.glBufferCreateTotal=" + snapshot.createTotal()
                + " dh.glBufferLiveCountPeak=" + snapshot.peakBufferCount()
                + " dh.glBufferCapacityChangeTotal=" + snapshot.capacityChangeTotal()
                + " dh.glBufferDestroyTotal=" + snapshot.destroyTotal()
                + " dh.glBufferCountReported=" + snapshot.dhReportedBufferCount();
    }

    /** Pure DH buffer-id ledger so lifecycle accounting can be checked without an OpenGL context. */
    static final class BufferLedger {
        private final ConcurrentHashMap<Integer, Long> capacityByBufferId = new ConcurrentHashMap<>();
        private final AtomicInteger peakBufferCount = new AtomicInteger();
        private final AtomicLong liveCapacityBytes = new AtomicLong();
        private final AtomicLong peakCapacityBytes = new AtomicLong();
        private final AtomicLong createTotal = new AtomicLong();
        private final AtomicLong capacityChangeTotal = new AtomicLong();
        private final AtomicLong destroyTotal = new AtomicLong();
        private final AtomicInteger dhReportedBufferCount = new AtomicInteger(-1);

        /** Records a new live id at zero reported storage bytes. */
        void created(int id, int reportedBufferCount) {
            this.dhReportedBufferCount.set(reportedBufferCount);
            if (id == 0) return;
            this.createTotal.incrementAndGet();
            this.setCapacity(id, 0L, false);
            this.peakBufferCount.accumulateAndGet(this.capacityByBufferId.size(), Math::max);
        }

        /** Reconciles an id's logical capacity with DH's size field after a completed upload. */
        void capacityReported(int id, int capacityBytes, int reportedBufferCount) {
            this.dhReportedBufferCount.set(reportedBufferCount);
            if (id == 0 || capacityBytes < 0) return;
            this.setCapacity(id, capacityBytes, true);
        }

        /** Retires an id after the GL deletion callback returns, not when async deletion is queued. */
        void deleted(int id, int reportedBufferCount) {
            this.dhReportedBufferCount.set(reportedBufferCount);
            if (id == 0) return;
            Long removedCapacity = this.capacityByBufferId.remove(id);
            if (removedCapacity != null) {
                this.liveCapacityBytes.addAndGet(-removedCapacity);
            }
            this.destroyTotal.incrementAndGet();
        }

        /** Returns an immutable snapshot of the logical live gauges and lifetime counts. */
        BufferLedgerSnapshot snapshot() {
            return new BufferLedgerSnapshot(this.capacityByBufferId.size(), this.peakBufferCount.get(),
                    this.liveCapacityBytes.get(), this.peakCapacityBytes.get(), this.createTotal.get(),
                    this.capacityChangeTotal.get(), this.destroyTotal.get(), this.dhReportedBufferCount.get());
        }

        private void setCapacity(int id, long capacityBytes, boolean countCapacityChange) {
            this.capacityByBufferId.compute(id, (ignored, previousCapacity) -> {
                if (countCapacityChange
                        && (previousCapacity == null || previousCapacity.longValue() != capacityBytes)) {
                    this.capacityChangeTotal.incrementAndGet();
                }
                long oldBytes = previousCapacity == null ? 0L : previousCapacity;
                long liveBytes = this.liveCapacityBytes.addAndGet(capacityBytes - oldBytes);
                this.peakCapacityBytes.accumulateAndGet(liveBytes, Math::max);
                return capacityBytes;
            });
        }
    }

    /** Immutable logical buffer accounting captured by {@link BufferLedger#snapshot()}. */
    record BufferLedgerSnapshot(int liveBufferCount, int peakBufferCount, long liveCapacityBytesReported,
                                long peakCapacityBytesReported, long createTotal,
                                long capacityChangeTotal, long destroyTotal,
                                int dhReportedBufferCount) {
    }
}
