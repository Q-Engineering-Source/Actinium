package com.gtnewhorizons.angelica.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGE_COUNT;
import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGES_PER_RADIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWedgeArcTest {

    @Test
    void aSpanCoveringTheWholeFanDrawsEveryWedge() {
        assertTrue(CloudWedgeArc.of(0.0, Math.PI).wholeFan());
        assertTrue(CloudWedgeArc.of(-2.4, Math.PI).wholeFan());
    }

    @Test
    void theArcContainsTheWedgeTheCameraFaces() {
        for (double facing = -3.0; facing <= 3.0; facing += 0.25) {
            final CloudWedgeArc arc = CloudWedgeArc.of(facing, 0.4);
            assertFalse(arc.wholeFan(), "facing " + facing);
            assertTrue(wedges(arc).contains(centreWedge(facing)), "facing " + facing);
        }
    }

    @Test
    void aWiderSpanNeverDropsAWedgeOfANarrowerOne() {
        final List<Integer> narrow = wedges(CloudWedgeArc.of(0.3, 0.25));
        final List<Integer> wide = wedges(CloudWedgeArc.of(0.3, 0.6));
        assertTrue(wide.containsAll(narrow), "wide " + wide + " narrow " + narrow);
    }

    @Test
    void anArcAcrossTheFirstWedgeWrapsAround() {
        final CloudWedgeArc arc = CloudWedgeArc.of(-Math.PI, 0.4);
        assertFalse(arc.wholeFan());
        assertTrue(arc.from() > arc.to(), "arc must wrap, got " + arc.from() + " to " + arc.to());
        assertTrue(wedges(arc).contains(0));
    }

    @Test
    void theArcRepeatsOncePerTurn() {
        final CloudWedgeArc first = CloudWedgeArc.of(0.75, 0.5);
        final CloudWedgeArc nextTurn = CloudWedgeArc.of(0.75 + 2.0 * Math.PI, 0.5);
        assertEquals(wedges(first), wedges(nextTurn));
    }

    private static int centreWedge(double facing) {
        return Math.floorMod((int) Math.floor((facing + Math.PI) * WEDGES_PER_RADIAN), WEDGE_COUNT);
    }

    private static List<Integer> wedges(CloudWedgeArc arc) {
        final List<Integer> covered = new ArrayList<>();
        if (arc.wholeFan()) {
            for (int wedge = 0; wedge < WEDGE_COUNT; wedge++) covered.add(wedge);
            return covered;
        }
        if (arc.from() <= arc.to()) {
            for (int wedge = arc.from(); wedge <= arc.to(); wedge++) covered.add(wedge);
            return covered;
        }
        for (int wedge = arc.from(); wedge < WEDGE_COUNT; wedge++) covered.add(wedge);
        for (int wedge = 0; wedge <= arc.to(); wedge++) covered.add(wedge);
        return covered;
    }
}
