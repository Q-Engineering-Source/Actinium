package com.dhj.actinium.hud.system;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GpuStats;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Samples CPU, memory and GPU metrics through OSHI on one dedicated thread.
 *
 * <p>The render thread only starts and stops the source. The hardware abstraction layer, the choice
 * of graphics card and the GPU session are created on the sampling thread, which owns them until it
 * ends. CPU/memory and GPU are sampled as separate segments so a failing backend cannot take the
 * other values down, and each segment reports its failure once per start instead of once per
 * interval. A failed segment keeps the values of the last successful sample, a value the platform
 * does not report stays empty, and a segment that recovers publishes again on the next interval.
 */
final class OshiSystemMetricsSourceImpl implements SystemMetricsSource {
    private static final Logger LOGGER = LogManager.getLogger("Actinium PerformanceIndicator");
    private static final String SAMPLING_THREAD_NAME = "Actinium-PerformanceIndicator";
    private static final long SAMPLING_THREAD_JOIN_TIMEOUT_MILLIS = 50L;
    private static final double MEGAHERTZ_TO_HERTZ = 1_000_000.0;
    private static final double FRACTION_TO_PERCENT = 100.0;

    private final Supplier<HardwareAbstractionLayer> hardwareSupplier;

    private volatile SystemMetricsSnapshot latest = SystemMetricsSnapshot.empty();
    private SamplingState samplingState;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> samplingTask;
    private boolean closed;

    /**
     * @param hardwareSupplier opens the hardware abstraction layer; it is called on the sampling
     *                         thread, never on the caller of {@link #start(String, int)}
     */
    OshiSystemMetricsSourceImpl(Supplier<HardwareAbstractionLayer> hardwareSupplier) {
        this.hardwareSupplier = Objects.requireNonNull(hardwareSupplier, "hardwareSupplier");
    }

    @Override
    public void start(String rendererName, int refreshIntervalMillis) {
        Objects.requireNonNull(rendererName, "rendererName");
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("refreshIntervalMillis must be positive: " + refreshIntervalMillis);
        }
        if (this.closed) {
            throw new IllegalStateException("the metrics source is closed");
        }
        if (this.executor != null) {
            if (!this.executor.isShutdown()) {
                throw new IllegalStateException("the metrics source is already sampling");
            }
            stop();
            if (this.executor != null) {
                throw new IllegalStateException("the previous sampling thread has not stopped");
            }
        }
        SamplingState state = new SamplingState(rendererName, this.hardwareSupplier);
        this.latest = SystemMetricsSnapshot.empty();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            workerBody -> newSamplingThread(workerBody, state));
        try {
            this.samplingTask = executor.scheduleWithFixedDelay(
                () -> publish(state), 0L, refreshIntervalMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            throw failure;
        }
        this.samplingState = state;
        this.executor = executor;
    }

    @Override
    public SystemMetricsSnapshot latest() {
        return this.latest;
    }

    @Override
    public void setRefreshIntervalMillis(int refreshIntervalMillis) {
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("refreshIntervalMillis must be positive: " + refreshIntervalMillis);
        }
        if (this.closed) {
            throw new IllegalStateException("the metrics source is closed");
        }
        ScheduledExecutorService executor = this.executor;
        ScheduledFuture<?> task = this.samplingTask;
        SamplingState state = this.samplingState;
        if (executor == null || task == null || state == null || executor.isShutdown()) {
            throw new IllegalStateException("the metrics source is not sampling");
        }
        task.cancel(false);
        this.samplingTask = executor.scheduleWithFixedDelay(
            () -> publish(state), refreshIntervalMillis, refreshIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = this.executor;
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        if (awaitSamplingThread(this.samplingState)) {
            this.samplingTask = null;
            this.samplingState = null;
            this.executor = null;
        }
    }

    @Override
    public void close() {
        this.closed = true;
        stop();
    }

    private void publish(SamplingState state) {
        state.sample();
        this.latest = state.snapshot();
    }

    /**
     * Wraps the executor worker so the GPU session is released on the sampling thread even when the
     * thread never runs a sample again. {@code shutdownNow} ends the worker loop rather than the
     * sampling task, and an idle worker is interrupted while it waits for the next interval.
     */
    private static Thread newSamplingThread(Runnable workerBody, SamplingState state) {
        Thread thread = new Thread(() -> {
            try {
                workerBody.run();
            } finally {
                state.closeGpuSession();
            }
        }, SAMPLING_THREAD_NAME);
        thread.setDaemon(true);
        state.attachSamplingThread(thread);
        return thread;
    }

    private static boolean awaitSamplingThread(SamplingState state) {
        Thread thread = state == null ? null : state.samplingThread();
        if (thread == null) {
            return true;
        }
        try {
            thread.join(SAMPLING_THREAD_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for the performance sampling thread to stop", interrupted);
            return false;
        }
        if (thread.isAlive()) {
            LOGGER.warn("The performance sampling thread is still alive after {} ms", SAMPLING_THREAD_JOIN_TIMEOUT_MILLIS);
            return false;
        }
        return true;
    }

    private static OptionalDouble positive(double value) {
        return value > 0 ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    private static OptionalDouble nonNegative(double value) {
        return value >= 0 ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    private static OptionalDouble hertzFromMegahertz(long megahertz) {
        return positive(megahertz * MEGAHERTZ_TO_HERTZ);
    }

    /** Values of the CPU/memory segment; replaced as a whole when a sample succeeds. */
    private record CpuMemoryMetrics(OptionalDouble memoryUsedBytes, OptionalDouble memoryTotalBytes,
                                    OptionalDouble cpuLoadPercent, OptionalDouble cpuFrequencyHz) {
        static CpuMemoryMetrics empty() {
            return new CpuMemoryMetrics(
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());
        }
    }

    /** Values of the GPU segment; replaced as a whole when a sample succeeds. */
    private record GpuMetrics(OptionalDouble gpuLoadPercent, OptionalDouble gpuCoreFrequencyHz,
                              OptionalDouble vramUsedBytes, OptionalDouble vramTotalBytes,
                              OptionalDouble vramFrequencyHz) {
        static GpuMetrics empty() {
            return new GpuMetrics(
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty());
        }
    }

    /** Everything one start owns, created on the render thread and touched by the sampling thread only. */
    private static final class SamplingState {
        private enum Metric {
            CPU_LOAD,
            CPU_FREQUENCY,
            MEMORY_USED,
            MEMORY_TOTAL,
            GPU_LOAD,
            GPU_CORE_FREQUENCY,
            VRAM_USED,
            VRAM_TOTAL,
            VRAM_FREQUENCY
        }

        private final String rendererName;
        private final Supplier<HardwareAbstractionLayer> hardwareSupplier;
        private final Set<Metric> unavailableLogged = EnumSet.noneOf(Metric.class);

        private HardwareAbstractionLayer hardware;
        private boolean hardwareUnavailable;

        private CentralProcessor processor;
        private GlobalMemory memory;
        private long[] previousCpuTicks;
        private CpuMemoryMetrics cpuMemory = CpuMemoryMetrics.empty();
        private boolean cpuMemoryFailureReported;

        private boolean graphicsCardResolved;
        private GraphicsCard graphicsCard;
        private GpuStats gpuSession;
        private GpuMetrics gpu = GpuMetrics.empty();
        private boolean gpuFailureReported;

        private Thread samplingThread;

        SamplingState(String rendererName, Supplier<HardwareAbstractionLayer> hardwareSupplier) {
            this.rendererName = rendererName;
            this.hardwareSupplier = hardwareSupplier;
        }

        void attachSamplingThread(Thread thread) {
            this.samplingThread = thread;
        }

        Thread samplingThread() {
            return this.samplingThread;
        }

        void sample() {
            HardwareAbstractionLayer hardware = hardware();
            if (hardware == null) {
                return;
            }
            sampleCpuAndMemory(hardware);
            sampleGpu(hardware);
        }

        SystemMetricsSnapshot snapshot() {
            CpuMemoryMetrics cpu = this.cpuMemory;
            GpuMetrics gpu = this.gpu;
            return new SystemMetricsSnapshot(
                cpu.memoryUsedBytes(),
                cpu.memoryTotalBytes(),
                cpu.cpuLoadPercent(),
                cpu.cpuFrequencyHz(),
                gpu.gpuLoadPercent(),
                gpu.gpuCoreFrequencyHz(),
                gpu.vramUsedBytes(),
                gpu.vramTotalBytes(),
                gpu.vramFrequencyHz());
        }

        void closeGpuSession() {
            GpuStats session = this.gpuSession;
            if (session == null) {
                return;
            }
            this.gpuSession = null;
            try {
                session.close();
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("Failed to close the GPU metrics session", failure);
            }
        }

        private HardwareAbstractionLayer hardware() {
            if (this.hardwareUnavailable) {
                return null;
            }
            if (this.hardware == null) {
                try {
                    this.hardware = Objects.requireNonNull(this.hardwareSupplier.get(), "hardware");
                } catch (RuntimeException | LinkageError failure) {
                    this.hardwareUnavailable = true;
                    LOGGER.warn("The hardware sensor library failed to initialize; CPU and GPU metrics stay unavailable until the next start", failure);
                    return null;
                }
            }
            return this.hardware;
        }

        private void sampleCpuAndMemory(HardwareAbstractionLayer hardware) {
            try {
                CentralProcessor processor = processor(hardware);
                GlobalMemory memory = memory(hardware);
                OptionalDouble cpuLoad = cpuLoad(processor);
                OptionalDouble cpuFrequency = cpuFrequency(processor);
                long total = memory.getTotal();
                long available = memory.getAvailable();
                OptionalDouble memoryTotal = positive(total);
                OptionalDouble memoryUsed = total > 0 && available >= 0 && total - available >= 0
                    ? OptionalDouble.of(total - available)
                    : OptionalDouble.empty();
                logIfEmpty(Metric.CPU_FREQUENCY, cpuFrequency);
                logIfEmpty(Metric.MEMORY_USED, memoryUsed);
                logIfEmpty(Metric.MEMORY_TOTAL, memoryTotal);
                this.cpuMemory = new CpuMemoryMetrics(memoryUsed, memoryTotal, cpuLoad, cpuFrequency);
            } catch (RuntimeException | LinkageError failure) {
                reportCpuMemoryFailure(failure);
            }
        }

        private CentralProcessor processor(HardwareAbstractionLayer hardware) {
            if (this.processor == null) {
                this.processor = Objects.requireNonNull(hardware.getProcessor(), "processor");
            }
            return this.processor;
        }

        private GlobalMemory memory(HardwareAbstractionLayer hardware) {
            if (this.memory == null) {
                this.memory = Objects.requireNonNull(hardware.getMemory(), "memory");
            }
            return this.memory;
        }

        private OptionalDouble cpuLoad(CentralProcessor processor) {
            long[] ticks = processor.getSystemCpuLoadTicks();
            long[] previousTicks = this.previousCpuTicks;
            this.previousCpuTicks = ticks;
            if (previousTicks == null) {
                return OptionalDouble.empty();
            }
            OptionalDouble load = nonNegative(processor.getSystemCpuLoadBetweenTicks(previousTicks) * FRACTION_TO_PERCENT);
            logIfEmpty(Metric.CPU_LOAD, load);
            return load;
        }

        private static OptionalDouble cpuFrequency(CentralProcessor processor) {
            long[] frequencies = processor.getCurrentFreq();
            long sum = 0L;
            int count = 0;
            for (long frequency : frequencies) {
                if (frequency > 0) {
                    sum += frequency;
                    count++;
                }
            }
            return count == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) sum / count);
        }

        private void sampleGpu(HardwareAbstractionLayer hardware) {
            GraphicsCard card = graphicsCard(hardware);
            if (card == null) {
                return;
            }
            try {
                GpuStats session = this.gpuSession;
                boolean primedNow = false;
                if (session == null) {
                    session = card.createStatsSession();
                    this.gpuSession = session;
                    session.getGpuUtilization();
                    primedNow = true;
                }
                OptionalDouble gpuLoad = primedNow
                    ? OptionalDouble.empty()
                    : nonNegative(session.getGpuUtilization());
                OptionalDouble coreFrequency = hertzFromMegahertz(session.getCoreClockMhz());
                OptionalDouble vramUsed = nonNegative(session.getVramUsed());
                OptionalDouble vramTotal = positive(card.getVRam());
                OptionalDouble vramFrequency = hertzFromMegahertz(session.getMemoryClockMhz());
                if (!primedNow) {
                    logIfEmpty(Metric.GPU_LOAD, gpuLoad);
                }
                logIfEmpty(Metric.GPU_CORE_FREQUENCY, coreFrequency);
                logIfEmpty(Metric.VRAM_USED, vramUsed);
                logIfEmpty(Metric.VRAM_TOTAL, vramTotal);
                logIfEmpty(Metric.VRAM_FREQUENCY, vramFrequency);
                this.gpu = new GpuMetrics(gpuLoad, coreFrequency, vramUsed, vramTotal, vramFrequency);
            } catch (RuntimeException | LinkageError failure) {
                reportGpuFailure(failure);
                closeGpuSession();
            }
        }

        private GraphicsCard graphicsCard(HardwareAbstractionLayer hardware) {
            if (this.graphicsCardResolved) {
                return this.graphicsCard;
            }
            this.graphicsCardResolved = true;
            try {
                List<GraphicsCard> cards = hardware.getGraphicsCards();
                if (cards.isEmpty()) {
                    LOGGER.warn("The hardware sensor library reports no graphics card; GPU metrics stay unavailable until the next start");
                    return null;
                }
                if (cards.size() == 1) {
                    this.graphicsCard = cards.get(0);
                    return this.graphicsCard;
                }
                List<String> cardNames = cards.stream().map(GraphicsCard::getName).toList();
                OptionalInt matchedIndex = GpuNameMatcher.match(this.rendererName, cardNames);
                if (matchedIndex.isEmpty()) {
                    LOGGER.warn("No graphics card uniquely matches the renderer '{}'; GPU metrics stay unavailable until the next start. Cards: {}",
                        this.rendererName, cardNames);
                    return null;
                }
                this.graphicsCard = cards.get(matchedIndex.getAsInt());
                return this.graphicsCard;
            } catch (RuntimeException | LinkageError failure) {
                reportGpuFailure(failure);
                return null;
            }
        }

        private void logIfEmpty(Metric metric, OptionalDouble value) {
            if (value.isPresent() || !this.unavailableLogged.add(metric)) {
                return;
            }
            LOGGER.info("The hardware sensor library does not report {} on this system; the indicator shows N/A", metric);
        }

        private void reportCpuMemoryFailure(Throwable failure) {
            if (this.cpuMemoryFailureReported) {
                return;
            }
            this.cpuMemoryFailureReported = true;
            LOGGER.warn("Sampling CPU and memory metrics failed; the indicator keeps the last values until the next start", failure);
        }

        private void reportGpuFailure(Throwable failure) {
            if (this.gpuFailureReported) {
                return;
            }
            this.gpuFailureReported = true;
            LOGGER.warn("Sampling GPU metrics failed; the indicator keeps the last values until the next start", failure);
        }
    }
}
