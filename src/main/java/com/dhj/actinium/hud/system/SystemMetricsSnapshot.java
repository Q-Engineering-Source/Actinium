package com.dhj.actinium.hud.system;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One sample of the hardware metrics the performance indicator shows.
 *
 * <p>A value is empty when the platform does not report it, so a consumer renders a placeholder
 * instead of a zero that looks like a measurement. The instance is immutable and can be handed to
 * another thread as is.
 *
 * @param memoryUsedBytes system memory in use, in bytes
 * @param memoryTotalBytes installed system memory, in bytes
 * @param cpuLoadPercent CPU load over the sampling interval, in percent
 * @param cpuFrequencyHz mean current frequency of the logical processors, in hertz
 * @param gpuLoadPercent GPU core utilization, in percent
 * @param gpuCoreFrequencyHz current GPU core clock, in hertz
 * @param vramUsedBytes dedicated video memory in use, in bytes
 * @param vramTotalBytes dedicated video memory of the selected graphics card, in bytes
 * @param vramFrequencyHz current GPU memory clock, in hertz
 */
public record SystemMetricsSnapshot(
    OptionalDouble memoryUsedBytes,
    OptionalDouble memoryTotalBytes,
    OptionalDouble cpuLoadPercent,
    OptionalDouble cpuFrequencyHz,
    OptionalDouble gpuLoadPercent,
    OptionalDouble gpuCoreFrequencyHz,
    OptionalDouble vramUsedBytes,
    OptionalDouble vramTotalBytes,
    OptionalDouble vramFrequencyHz) {

    public SystemMetricsSnapshot {
        Objects.requireNonNull(memoryUsedBytes, "memoryUsedBytes");
        Objects.requireNonNull(memoryTotalBytes, "memoryTotalBytes");
        Objects.requireNonNull(cpuLoadPercent, "cpuLoadPercent");
        Objects.requireNonNull(cpuFrequencyHz, "cpuFrequencyHz");
        Objects.requireNonNull(gpuLoadPercent, "gpuLoadPercent");
        Objects.requireNonNull(gpuCoreFrequencyHz, "gpuCoreFrequencyHz");
        Objects.requireNonNull(vramUsedBytes, "vramUsedBytes");
        Objects.requireNonNull(vramTotalBytes, "vramTotalBytes");
        Objects.requireNonNull(vramFrequencyHz, "vramFrequencyHz");
    }

    /** {@return a snapshot without a single reported value} */
    public static SystemMetricsSnapshot empty() {
        return new SystemMetricsSnapshot(
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty());
    }
}
