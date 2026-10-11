package com.dhj.actinium.render.pacing;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend.GateSample;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;

import java.util.function.LongConsumer;

/**
 * Frame pacing for the client's render loop.
 *
 * <p>Vanilla nodes the frame rate by asking {@code Display.sync} to sleep once per frame, after the
 * frame has already been presented. That limits the average rate but not the interval between
 * presents: the sleep starts from whenever the frame happened to finish, so every bit of jitter in
 * the frame's own cost is passed straight through to the panel, and on a vsync'd swap it stacks on
 * top of the driver's own block.
 *
 * <p>This pacer splits the frame in two. It measures what the frame actually costs, works out when
 * the frame has to <em>start</em> for its present to land on the panel's cadence, and sleeps there;
 * then, at the present, it walks the phase back onto the cadence rather than after it. The panel's
 * refresh period comes from the render backend, and when the driver's swap is proven to block on the
 * refresh grid the pacer hands the pacing to it and only keeps the phase.
 *
 * <p>The three entry points map onto the render loop: {@link #endFrame} once per frame where the
 * loop would have yielded, {@link #beforePresent} immediately before the swap, and
 * {@link #invalidate} whenever the source of the pacing changed (a display mode change, entering or
 * leaving a world).
 */
public final class FramePacer {

    /**
     * The slice of the render backend the pacer reads and drives.
     *
     * <p>Narrow on purpose: it is the pacer's whole view of the outside world, which keeps the
     * pacing logic testable against a simulated display.
     */
    interface Backend {
        VSyncMode effectiveVSyncMode();
        long refreshPeriodNanos();
        boolean vsyncHonored();
        int displayGeneration();
        void awaitPresent();
        void readGateSample(GateSample out);
        void pumpDisplayMessages();
        void parkNanos(long nanos);
        long nanoTime();
    }

    private static final class RenderBackendAdapter implements Backend {
        @Override public VSyncMode effectiveVSyncMode() { return BackendManager.RENDER_BACKEND.getEffectiveVSyncMode(); }
        @Override public long refreshPeriodNanos() { return BackendManager.RENDER_BACKEND.refreshPeriodNanos(); }
        @Override public boolean vsyncHonored() { return BackendManager.RENDER_BACKEND.vsyncHonored(); }
        @Override public int displayGeneration() { return BackendManager.RENDER_BACKEND.displayGeneration(); }
        @Override public void awaitPresent() { BackendManager.RENDER_BACKEND.awaitPresent(); }
        @Override public void readGateSample(GateSample out) { BackendManager.RENDER_BACKEND.readGateSample(out); }
        @Override public void pumpDisplayMessages() { BackendManager.RENDER_BACKEND.pumpDisplayMessages(); }
        @Override public void parkNanos(long nanos) { BackendManager.RENDER_BACKEND.parkNanos(nanos); }
        @Override public long nanoTime() { return System.nanoTime(); }
    }

    private static final Tracy.ZoneId Z_PACER_WAIT = Tracy.zoneId("pacerBackpressure", Tracy.COLOR_SWAP);
    private static final Tracy.ZoneId Z_PACER_BEGIN = Tracy.zoneId("pacerBegin", Tracy.COLOR_CLIENT);
    private static final Tracy.ZoneId Z_PACER_PRESENT_SLEEP = Tracy.zoneId("pacerPresentSleep", Tracy.COLOR_SWAP);

    /** The pacing configuration in force for a frame, kept so it can be reported without re-reading the backend. */
    private record Pacing(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, boolean locked, boolean probing) {}

    private static final Pacing UNPACED = new Pacing(VSyncMode.ON, 0, 0, 0, false, false);

    private static Backend BACKEND = new RenderBackendAdapter();

    private static void parkNanos(long nanos) {
        BACKEND.parkNanos(nanos);
    }

    private static long nanoTime() {
        return BACKEND.nanoTime();
    }

    private static PacerSleeper sleeper = new PacerSleeper(FramePacer::nanoTime, FramePacer::parkNanos, true);
    static PacerCore core = new PacerCore(FramePacer::nanoTime, sleeper);
    private static final GateSample sample = new GateSample();

    private static boolean warnedUncapped;
    private static int lastDisplayGeneration;

    private static Pacing current = UNPACED;
    private static Pacing logged;

    private FramePacer() {}

    /**
     * Registers work the renderer can do in the slack before the next present target instead of
     * sleeping through it.
     */
    public static void setIdleWork(LongConsumer work) {
        core.setIdleWork(work);
    }

    /** Drops the phase lock so the next frames re-establish it against the current display. */
    public static void invalidate() {
        core.invalidate();
    }

    /** Whether the frame just ended was paced, i.e. whether the loop must not pace it itself. */
    public static boolean pacedLastFrame() {
        return core.paced();
    }

    public static void beginStats() {
        sleeper.resetStats();
    }

    public static String endStats() {
        final String summary = sleeper.summary(configLine());
        Tracy.message(summary);
        return summary;
    }

    /**
     * The pacer's line for the periodic perf report: the wait quality over the interval just
     * reported, followed by what the last frame achieved. The two belong together — a report that
     * shows the pacer waiting badly also has to show whether the gate was locked and how much lead
     * the present carried. Resets the interval, matching the report's dump-and-reset contract.
     */
    public static String statsSummary() {
        final String summary = endStats();
        beginStats();
        return summary + " last=[gateUs=" + gateDurationNanos() / 1000L
            + " gpuWaitUs=" + gpuWaitNanos() / 1000L
            + " slackUs=" + slackNanos() / 1000L
            + " spinUs=" + spinNanos() / 1000L
            + " marginUs=" + marginNanos() / 1000L
            + " presentUs=" + presentIntervalNanos() / 1000L
            + " idleOvershootUs=" + idleOvershootNanos() / 1000L
            + " locked=" + locked() + " probing=" + probing() + "]";
    }

    private static String configLine(Pacing pacing) {
        return "cfg=[" + pacing.mode() + " refresh=" + pacing.refreshHz() + "Hz cap=" + pacing.capHz()
            + " ceiling=" + pacing.effectiveCapHz() + "Hz]";
    }

    private static String configLine() {
        return configLine(current);
    }

    /**
     * Ends the frame: reads what the driver did to the swap that just happened, waits for the
     * renderer's own backpressure, sleeps until the next frame's start deadline and pumps window
     * messages.
     *
     * @param capHz the frame rate limit to apply, or zero for no limit
     * @param renderAheadWait renderer work that must complete before the frame may start, or null
     * @return the interval between the last two presents, in nanoseconds
     */
    public static long endFrame(int capHz, Runnable renderAheadWait) {
        final VSyncMode mode = BACKEND.effectiveVSyncMode();
        final int cap = Math.max(capHz, 0);
        final long refreshPeriodNanos = BACKEND.refreshPeriodNanos();
        final boolean honored = BACKEND.vsyncHonored();
        final int refreshHz = refreshHz(mode, cap, refreshPeriodNanos);

        core.setRefreshPeriodNanos(refreshPeriodNanos);
        core.setTearFree(mode.tearFree());
        core.setVsyncOn(mode == VSyncMode.ON && honored);
        core.setCap(cap);

        final int generation = BACKEND.displayGeneration();
        if (generation != lastDisplayGeneration) {
            lastDisplayGeneration = generation;
            core.invalidate();
        }

        if (mode == VSyncMode.ON && honored) {
            BACKEND.awaitPresent();
        }
        BACKEND.readGateSample(sample);
        core.onGateSample(sample.durationNanos, sample.endNanos);
        core.onGpuWait(sample.gpuWaitNanos);

        Tracy.beginZone(Z_PACER_WAIT);
        try {
            if (renderAheadWait != null) {
                final long waitStart = nanoTime();
                renderAheadWait.run();
                core.onGpuWait(nanoTime() - waitStart);
            }
        } finally {
            Tracy.endZone();
        }

        Tracy.beginZone(Z_PACER_BEGIN);
        try {
            core.beginFrame(nanoTime());
        } finally {
            Tracy.endZone();
        }

        BACKEND.pumpDisplayMessages();
        core.markFrameStart(nanoTime());

        current = new Pacing(mode, refreshHz, cap, core.effectiveCapHz(), core.locked(), core.probing());
        maybeLog(current);

        return core.lastPresentIntervalNanos();
    }

    /** Hands the core the driver block seen since the last frame and waits until the present target. */
    public static void beforePresent() {
        final long firstClearBlockNanos = GLStateManager.takeFirstClearBlockNanos();
        Tracy.beginZone(Z_PACER_PRESENT_SLEEP);
        try {
            core.beforePresent(nanoTime(), firstClearBlockNanos);
        } finally {
            Tracy.endZone();
        }
    }

    private static void maybeLog(Pacing pacing) {
        if (pacing.equals(logged)) {
            return;
        }
        logged = pacing;

        final String line = pacingLine(pacing.mode(), pacing.refreshHz(), pacing.capHz(), pacing.effectiveCapHz(),
            pacing.locked(), pacing.probing());
        final boolean wallClock = pacing.mode() == VSyncMode.ON && !pacing.locked() && !pacing.probing();
        if (wallClock) ActiniumRuntime.logger().warn(line);
        else ActiniumRuntime.logger().info(line);
        Tracy.message(line);
    }

    static String pacingLine(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, boolean locked, boolean probing) {
        final StringBuilder sb = new StringBuilder("Frame pacing: ");
        sb.append(switch (mode) {
            case OFF -> "vsync off";
            case MAILBOX -> "mailbox";
            case ON -> "vsync";
        });
        if (mode.tearFree() && refreshHz > 0) sb.append(' ').append(refreshHz).append("Hz");
        if (mode == VSyncMode.ON) {
            if (locked) sb.append(", hardware paced");
            else if (probing) sb.append(", probing");
            else sb.append(", driver not blocking, paced by wall clock");
        }
        if (capHz > 0) {
            sb.append(", cap ").append(capHz);
            if (effectiveCapHz > 0 && effectiveCapHz != capHz) {
                sb.append(" -> ").append(effectiveCapHz);
            }
        }
        return sb.toString();
    }

    private static int refreshHz(VSyncMode mode, int capHz, long refreshPeriodNanos) {
        final int hz = RenderBackend.hzFromPeriod(refreshPeriodNanos);
        if (hz == 0 && capHz == 0 && mode.tearFree() && !warnedUncapped) {
            warnedUncapped = true;
            ActiniumRuntime.logger().warn("Display refresh rate is unknown, so the frame rate cannot be bounded if the driver ignores vsync. Set Max Framerate to bound it.");
        }
        return hz;
    }

    /** The F3 indicator naming the pacing in force, or null when nothing paces the frame. */
    public static String debugIndicator() {
        return debugIndicator(current.mode(), current.refreshHz(), current.capHz(), current.effectiveCapHz(), core.locked(), core.probing());
    }

    static String debugIndicator(VSyncMode mode, int refreshHz, int capHz, int effectiveCapHz, boolean locked, boolean probing) {
        final int cap = Math.max(capHz, 0);
        final boolean showCap = cap > 0;
        if (!mode.tearFree() && !showCap) return null;

        final StringBuilder sb = new StringBuilder(" [");
        if (mode.tearFree()) {
            sb.append(mode == VSyncMode.MAILBOX ? "mailbox" : "vsync");
            if (refreshHz > 0) sb.append(' ').append(refreshHz);
            if (mode == VSyncMode.ON) {
                if (probing) sb.append(", probing");
                else if (!locked) sb.append(", wall clock");
            }
            if (showCap) sb.append(", cap ").append(cap).append(" -> ").append(effectiveCapHz);
        } else {
            sb.append("cap ").append(cap);
        }
        return sb.append(']').toString();
    }

    public static long gateDurationNanos() { return sample.durationNanos; }

    public static long gpuWaitNanos() { return core.lastGpuWaitNanos(); }

    public static long slackNanos() { return core.slackNanos(); }

    public static long spinNanos() { return sleeper.lastFrameSpinNanos; }

    public static long marginNanos() { return core.marginNanos(); }

    public static long presentIntervalNanos() { return core.lastPresentIntervalNanos(); }

    public static long idleOvershootNanos() { return core.lastIdleOvershootNanos(); }

    public static int effectiveCapHz() { return core.effectiveCapHz(); }

    public static boolean locked() { return core.locked(); }

    public static boolean probing() { return core.probing(); }

    /**
     * Test entry point: points the pacer at the supplied backend and rebuilds the sleeper, the core
     * and every piece of pacing bookkeeping on top of it, so a test starts with no frame history.
     *
     * @return the backend that was installed before the call, for the caller to restore
     */
    static Backend installForTest(Backend backend) {
        final Backend previous = BACKEND;
        BACKEND = backend;
        sleeper = new PacerSleeper(FramePacer::nanoTime, FramePacer::parkNanos, true);
        core = new PacerCore(FramePacer::nanoTime, sleeper);
        lastDisplayGeneration = 0;
        warnedUncapped = false;
        current = UNPACED;
        logged = null;
        return previous;
    }
}
