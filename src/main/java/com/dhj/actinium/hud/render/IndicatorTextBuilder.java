package com.dhj.actinium.hud.render;

import com.dhj.actinium.hud.layout.IndicatorLayout;
import com.dhj.actinium.hud.layout.IndicatorRowType;
import com.dhj.actinium.hud.metric.IndicatorSnapshot;
import com.dhj.actinium.hud.metric.MetricStatistic;
import com.dhj.actinium.hud.system.SystemMetricsSnapshot;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Formats the enabled rows of the performance indicator into the lines the HUD draws.
 *
 * <p>The lines follow the order of {@link IndicatorLayout#enabledRows()}. Every value is formatted
 * with {@link Locale#ROOT}, so the decimal separator never depends on the game language. A value the
 * source did not report becomes {@code N/A} instead of a zero that reads like a measurement, and the
 * extremes of a metric stay visible while its current value is missing.
 *
 * <p>Labels are read from {@code gui.actinium.indicator.row.<id>} on every build, so a changed
 * language appears as soon as the caller rebuilds the lines.
 */
public final class IndicatorTextBuilder {
    private static final String LABEL_KEY_PREFIX = "gui.actinium.indicator.row.";
    private static final String NOT_AVAILABLE = "N/A";
    private static final String GIBIBYTE_UNIT = "GiB";
    private static final String MEGAHERTZ_UNIT = "MHz";
    private static final String GIGAHERTZ_UNIT = "GHz";

    private static final int FRAME_RATE_SCALE = 0;
    private static final int GIBIBYTE_SCALE = 1;
    private static final int PERCENT_SCALE = 1;
    private static final int MEGAHERTZ_SCALE = 0;
    private static final int GIGAHERTZ_SCALE = 2;

    private static final double BYTES_PER_GIBIBYTE = 1024.0 * 1024.0 * 1024.0;
    private static final double HERTZ_PER_MEGAHERTZ = 1.0e6;
    private static final double HERTZ_PER_GIGAHERTZ = 1.0e9;

    /**
     * Builds the lines of one publish.
     *
     * @param layout layout whose enabled rows are formatted, in display order
     * @param snapshot values of the most recent publish
     * @return one line per enabled row, in display order, empty when the layout holds no row
     */
    public List<String> build(IndicatorLayout layout, IndicatorSnapshot snapshot) {
        final List<IndicatorRowType> enabledRows = layout.enabledRows();
        final List<String> rows = new ArrayList<>(enabledRows.size());
        for (IndicatorRowType type : enabledRows) {
            rows.add(row(type, snapshot));
        }
        return List.copyOf(rows);
    }

    private String row(IndicatorRowType type, IndicatorSnapshot snapshot) {
        final SystemMetricsSnapshot system = snapshot.system();
        return switch (type) {
            case FPS -> statisticRow(type, snapshot.fps(), FRAME_RATE_SCALE);
            case FPS_LOW -> statisticRow(type, snapshot.fpsLow(), FRAME_RATE_SCALE);
            case MEMORY -> gibibyteStatisticRow(type, snapshot.memory());
            case CPU -> percentRow(type, system.cpuLoadPercent(), system.cpuFrequencyHz());
            case GPU -> percentRow(type, system.gpuLoadPercent(), system.gpuCoreFrequencyHz());
            case VRAM -> vramRow(type, system);
        };
    }

    private String statisticRow(IndicatorRowType type, MetricStatistic statistic, int scale) {
        return label(type) + ": " + format(statistic.current(), scale) + " " + range(statistic, scale);
    }

    private String gibibyteStatisticRow(IndicatorRowType type, MetricStatistic statistic) {
        final MetricStatistic gibibytes = divide(statistic, BYTES_PER_GIBIBYTE);
        return label(type) + ": " + format(gibibytes.current(), GIBIBYTE_SCALE, GIBIBYTE_UNIT)
            + " " + range(gibibytes, GIBIBYTE_SCALE);
    }

    private String percentRow(IndicatorRowType type, OptionalDouble loadPercent, OptionalDouble frequencyHz) {
        final String load = loadPercent.isEmpty()
            ? NOT_AVAILABLE
            : format(loadPercent.getAsDouble(), PERCENT_SCALE) + "%";
        return label(type) + ": " + load + " @ "
            + formatScaled(frequencyHz, HERTZ_PER_GIGAHERTZ, GIGAHERTZ_SCALE, GIGAHERTZ_UNIT);
    }

    private String vramRow(IndicatorRowType type, SystemMetricsSnapshot system) {
        return label(type) + ": " + vramUsage(system.vramUsedBytes(), system.vramTotalBytes()) + " @ "
            + formatScaled(system.vramFrequencyHz(), HERTZ_PER_MEGAHERTZ, MEGAHERTZ_SCALE, MEGAHERTZ_UNIT);
    }

    private static String vramUsage(OptionalDouble usedBytes, OptionalDouble totalBytes) {
        final OptionalDouble used = divide(usedBytes, BYTES_PER_GIBIBYTE);
        final OptionalDouble total = divide(totalBytes, BYTES_PER_GIBIBYTE);
        if (total.isEmpty()) {
            return format(used, GIBIBYTE_SCALE, GIBIBYTE_UNIT);
        }
        return format(used, GIBIBYTE_SCALE) + " / " + format(total, GIBIBYTE_SCALE) + " " + GIBIBYTE_UNIT;
    }

    private static String range(MetricStatistic statistic, int scale) {
        return "(min " + format(statistic.min(), scale) + " / max " + format(statistic.max(), scale) + ")";
    }

    private static String label(IndicatorRowType type) {
        return I18n.format(LABEL_KEY_PREFIX + type.id());
    }

    private static MetricStatistic divide(MetricStatistic statistic, double divisor) {
        return new MetricStatistic(
            divide(statistic.current(), divisor),
            divide(statistic.min(), divisor),
            divide(statistic.max(), divisor));
    }

    private static OptionalDouble divide(OptionalDouble value, double divisor) {
        return value.isEmpty() ? value : OptionalDouble.of(value.getAsDouble() / divisor);
    }

    private static String format(OptionalDouble value, int scale) {
        return value.isEmpty() ? NOT_AVAILABLE : format(value.getAsDouble(), scale);
    }

    private static String format(OptionalDouble value, int scale, String unit) {
        return value.isEmpty() ? NOT_AVAILABLE : format(value.getAsDouble(), scale) + " " + unit;
    }

    private static String formatScaled(OptionalDouble value, double divisor, int scale, String unit) {
        return value.isEmpty()
            ? NOT_AVAILABLE
            : format(value.getAsDouble() / divisor, scale) + " " + unit;
    }

    private static String format(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }
}
