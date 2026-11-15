package com.dhj.actinium.hud.system;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.OptionalInt;

/**
 * Chooses the metrics source the performance indicator samples the hardware with.
 *
 * <p>The OSHI backend needs JDK 25 for its FFM bindings and two jars the loader has to supply, so
 * every run that misses one of those falls back to a source that reports empty values. The checks
 * run on the class names and version string only, which keeps {@code oshi.ffm.SystemInfo} unreferenced
 * until the backend is known to exist.
 */
public final class SystemMetricsSources {
    private static final Logger LOGGER = LogManager.getLogger("Actinium PerformanceIndicator");
    private static final int MINIMUM_FEATURE_VERSION = 25;
    private static final String FFM_SYSTEM_INFO_RESOURCE = "oshi/ffm/SystemInfo.class";
    private static final String GPU_STATS_RESOURCE = "oshi/hardware/GpuStats.class";

    private SystemMetricsSources() {
    }

    /** {@return a source that samples the hardware metrics, or one that reports them as unavailable} */
    public static SystemMetricsSource create() {
        OptionalInt featureVersion = javaFeatureVersion();
        if (featureVersion.isEmpty()) {
            return new UnavailableSystemMetricsSourceImpl();
        }
        if (featureVersion.getAsInt() < MINIMUM_FEATURE_VERSION) {
            LOGGER.info("Hardware metrics need JDK {} or newer but this runtime is JDK {}; the indicator shows placeholders",
                MINIMUM_FEATURE_VERSION, featureVersion.getAsInt());
            return new UnavailableSystemMetricsSourceImpl();
        }
        if (!isPresent(FFM_SYSTEM_INFO_RESOURCE) || !isPresent(GPU_STATS_RESOURCE)) {
            LOGGER.warn("OSHI is missing from the classpath; hardware metrics stay unavailable");
            return new UnavailableSystemMetricsSourceImpl();
        }
        try {
            return OshiSystemMetricsSourceHolder.create();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("The OSHI hardware metrics source failed to initialize; hardware metrics stay unavailable", failure);
            return new UnavailableSystemMetricsSourceImpl();
        }
    }

    private static OptionalInt javaFeatureVersion() {
        String specificationVersion = System.getProperty("java.specification.version");
        if (specificationVersion == null) {
            LOGGER.warn("java.specification.version is unavailable; hardware metrics stay unavailable");
            return OptionalInt.empty();
        }
        String featureVersion = specificationVersion.startsWith("1.")
            ? specificationVersion.substring(2)
            : specificationVersion;
        try {
            return OptionalInt.of(Integer.parseInt(featureVersion));
        } catch (NumberFormatException failure) {
            LOGGER.warn("Unreadable java.specification.version '{}'; hardware metrics stay unavailable", specificationVersion, failure);
            return OptionalInt.empty();
        }
    }

    private static boolean isPresent(String resource) {
        ClassLoader loader = SystemMetricsSources.class.getClassLoader();
        return (loader != null ? loader.getResource(resource) : ClassLoader.getSystemResource(resource)) != null;
    }
}
