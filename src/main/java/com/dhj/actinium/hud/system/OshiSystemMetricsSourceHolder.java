package com.dhj.actinium.hud.system;

import oshi.ffm.SystemInfo;

/**
 * Creates the OSHI backed metrics source.
 *
 * <p>Kept apart from {@link SystemMetricsSources} so the reference to the FFM entry point stays out
 * of that class and is only resolved after the runtime checks passed. The hardware abstraction layer
 * is created by the supplier, which runs on the sampling thread.
 */
final class OshiSystemMetricsSourceHolder {
    private OshiSystemMetricsSourceHolder() {
    }

    static SystemMetricsSource create() {
        return new OshiSystemMetricsSourceImpl(() -> new SystemInfo().getHardware());
    }
}
