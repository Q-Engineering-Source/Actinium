package com.gtnewhorizons.angelica.render;

import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGE_COUNT;
import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGES_PER_RADIAN;

/**
 * Wedge indices the view can see, the range the renderer walks instead of drawing the whole fan.
 *
 * <p>The fan is split into wedges around the camera and the draw order groups the geometry by wedge, so the
 * view only needs the wedges inside its angular span. A span covering the fan cannot cull anything; the arc
 * then reports the whole fan and the renderer draws every range. A narrow arc that crosses the first wedge is
 * expressed as {@code from > to}, the two ranges it is made of.
 */
final class CloudWedgeArc {

    /** Quota of extra wedges per side, so a wedge straddling the arc edge is still drawn. */
    private static final double ARC_PADDING_WEDGES = 0.25;
    /** The arc of a view whose span covers the fan, where culling would drop visible wedges. */
    private static final CloudWedgeArc WHOLE_FAN = new CloudWedgeArc(true, 0, WEDGE_COUNT - 1);

    /**
     * Wedges the view needs.
     *
     * @param facingRadians camera yaw the wedges are centred on
     * @param halfSpanRadians half angular width of the view span around {@code facingRadians}
     */
    static CloudWedgeArc of(double facingRadians, double halfSpanRadians) {
        final double halfSpanWedges = halfSpanRadians * WEDGES_PER_RADIAN + ARC_PADDING_WEDGES;
        if (halfSpanWedges * 2.0 + 1.0 >= WEDGE_COUNT) return WHOLE_FAN;

        final double centre = (facingRadians + Math.PI) * WEDGES_PER_RADIAN;
        return new CloudWedgeArc(false,
            Math.floorMod((int) Math.floor(centre - halfSpanWedges), WEDGE_COUNT),
            Math.floorMod((int) Math.ceil(centre + halfSpanWedges), WEDGE_COUNT));
    }

    private final boolean wholeFan;
    private final int from;
    private final int to;

    private CloudWedgeArc(boolean wholeFan, int from, int to) {
        this.wholeFan = wholeFan;
        this.from = from;
        this.to = to;
    }

    /** Whether every wedge is visible, in which case the renderer draws the whole fan in one range. */
    boolean wholeFan() {
        return wholeFan;
    }

    /** First wedge of the arc, or of the first range when the arc wraps. */
    int from() {
        return from;
    }

    /** Last wedge of the arc, or of the second range when the arc wraps. */
    int to() {
        return to;
    }
}
