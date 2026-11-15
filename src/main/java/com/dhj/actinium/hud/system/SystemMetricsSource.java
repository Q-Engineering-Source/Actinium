package com.dhj.actinium.hud.system;

/**
 * Samples the hardware metrics the performance indicator shows.
 *
 * <p>{@link #start(String, int)}, {@link #setRefreshIntervalMillis(int)}, {@link #stop()} and
 * {@link #close()} are called on the render thread. Sampling runs on a worker owned by the source, so {@link #latest()} may be read from any
 * thread and always returns a complete, immutable snapshot.
 *
 * <p>A source is started while it is stopped and may be started again after {@link #stop()}.
 * {@link #close()} releases it for good; starting a closed source throws
 * {@link IllegalStateException}.
 */
public interface SystemMetricsSource extends AutoCloseable {
    /**
     * Starts sampling the hardware metrics.
     *
     * <p>The renderer name selects the graphics card on a machine with several of them. The first
     * sample only establishes the CPU and GPU baselines, so its utilization values are empty.
     *
     * @param rendererName renderer string the GL context reports, used to pick the graphics card
     * @param refreshIntervalMillis delay between two samples, in milliseconds
     * @throws IllegalStateException when the source is already sampling or has been closed
     * @throws IllegalArgumentException when {@code refreshIntervalMillis} is not positive
     */
    void start(String rendererName, int refreshIntervalMillis);

    /**
     * Changes the sampling period without resetting the CPU or GPU baselines.
     *
     * @param refreshIntervalMillis delay between two samples, in milliseconds
     * @throws IllegalStateException when the source is stopped, stopping or closed
     * @throws IllegalArgumentException when {@code refreshIntervalMillis} is not positive
     */
    void setRefreshIntervalMillis(int refreshIntervalMillis);

    /**
     * {@return the snapshot of the most recent sample, or {@link SystemMetricsSnapshot#empty()} before the first one}
     */
    SystemMetricsSnapshot latest();

    /** Stops sampling and releases the sampling thread and the native GPU session; no-op while stopped. */
    void stop();

    /**
     * Stops sampling and releases the source for good.
     *
     * <p>Repeated calls have no effect.
     */
    @Override
    void close();
}
