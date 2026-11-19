package com.dhj.actinium.hud.system;

import java.util.Objects;

/**
 * Source handed out when the runtime cannot sample hardware metrics.
 *
 * <p>It reports empty values forever and owns neither a thread nor a native session, so it stays
 * usable on runtimes below JDK 25 and on hosts whose loader does not carry the sensor library.
 */
final class UnavailableSystemMetricsSourceImpl implements SystemMetricsSource {
    private boolean sampling;
    private boolean closed;

    @Override
    public void start(String rendererName, int refreshIntervalMillis) {
        Objects.requireNonNull(rendererName, "rendererName");
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("refreshIntervalMillis must be positive: " + refreshIntervalMillis);
        }
        if (this.closed) {
            throw new IllegalStateException("the metrics source is closed");
        }
        if (this.sampling) {
            throw new IllegalStateException("the metrics source is already sampling");
        }
        this.sampling = true;
    }

    @Override
    public void setRefreshIntervalMillis(int refreshIntervalMillis) {
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("refreshIntervalMillis must be positive: " + refreshIntervalMillis);
        }
        if (this.closed) {
            throw new IllegalStateException("the metrics source is closed");
        }
        if (!this.sampling) {
            throw new IllegalStateException("the metrics source is not sampling");
        }
    }

    @Override
    public SystemMetricsSnapshot latest() {
        return SystemMetricsSnapshot.empty();
    }

    @Override
    public void stop() {
        this.sampling = false;
    }

    @Override
    public void close() {
        this.sampling = false;
        this.closed = true;
    }
}
