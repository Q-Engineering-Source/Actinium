package com.gtnewhorizons.angelica.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudDiscTest {

    private static final double EPSILON = 1.0E-6;

    @Test
    void centreOfTheDiscBelongsToTheMiddleWedge() {
        assertEquals(CloudDisc.WEDGE_COUNT / 2, CloudDisc.wedgeOf(0, 0));
    }

    @Test
    void aDenseRingSampleReachesEveryWedge() {
        final boolean[] reached = new boolean[CloudDisc.WEDGE_COUNT];
        for (int x = -320; x <= 320; x++) {
            for (int z = -320; z <= 320; z++) {
                final int distanceSquared = x * x + z * z;
                if (distanceSquared < 300 * 300 || distanceSquared > 320 * 320) continue;
                final int wedge = CloudDisc.wedgeOf(x, z);
                assertTrue(wedge >= 0 && wedge < CloudDisc.WEDGE_COUNT, "wedge " + wedge + " at " + x + "," + z);
                reached[wedge] = true;
            }
        }
        for (int wedge = 0; wedge < CloudDisc.WEDGE_COUNT; wedge++) {
            assertTrue(reached[wedge], "wedge " + wedge + " is never sampled");
        }
    }

    @Test
    void wedgesAdvanceByOneAroundTheCircle() {
        final int samples = 20000;
        final double radius = 10000.0;
        int previous = -1;
        for (int sample = 0; sample <= samples; sample++) {
            final double angle = 2.0 * Math.PI * sample / samples;
            final int x = (int) Math.round(radius * Math.cos(angle));
            final int z = (int) Math.round(radius * Math.sin(angle));
            final int wedge = CloudDisc.wedgeOf(x, z);
            if (previous >= 0 && wedge != previous) {
                assertEquals((previous + 1) % CloudDisc.WEDGE_COUNT, wedge, "after wedge " + previous);
            }
            previous = wedge;
        }
    }

    @Test
    void antipodalCellsLandHalfATurnApart() {
        for (int x = -48; x <= 48; x++) {
            for (int z = -48; z <= 48; z++) {
                if (x == 0 && z == 0) continue;
                assertEquals(
                    (CloudDisc.wedgeOf(x, z) + CloudDisc.WEDGE_COUNT / 2) % CloudDisc.WEDGE_COUNT,
                    CloudDisc.wedgeOf(-x, -z),
                    "at " + x + "," + z
                );
            }
        }
    }

    @Test
    void marginRejectsAnchorsOutsideTheSquareAndTheCircle() {
        assertTrue(CloudDisc.withinMargin(0, 0));
        assertTrue(CloudDisc.withinMargin(CloudDisc.MARGIN_CELLS, 0));
        assertTrue(CloudDisc.withinMargin(0, -CloudDisc.MARGIN_CELLS));
        assertTrue(CloudDisc.withinMargin(-11, 11));
        assertFalse(CloudDisc.withinMargin(CloudDisc.MARGIN_CELLS + 1, 0));
        assertFalse(CloudDisc.withinMargin(0, -CloudDisc.MARGIN_CELLS - 1));
        // Inside the square margin but outside the inscribed circle.
        assertFalse(CloudDisc.withinMargin(CloudDisc.MARGIN_CELLS, CloudDisc.MARGIN_CELLS));
        assertFalse(CloudDisc.withinMargin(12, 12));
    }

    @Test
    void discMembershipUsesTheSquaredRadiusAndAcceptsNegativeAnchors() {
        final int radius = 17;
        assertTrue(CloudDisc.withinDisc(0, 0, radius));
        assertTrue(CloudDisc.withinDisc(17, 0, radius));
        assertTrue(CloudDisc.withinDisc(-17, 0, radius));
        assertTrue(CloudDisc.withinDisc(-12, -12, radius));
        assertFalse(CloudDisc.withinDisc(12, 13, radius));
        // Inside the bounding square but outside the disc.
        assertFalse(CloudDisc.withinDisc(-17, -17, radius));
        assertFalse(CloudDisc.withinDisc(18, 0, radius));
        assertFalse(CloudDisc.withinDisc(-16, 16, radius));
    }

    @Test
    void emptyDiscOnlyContainsItsCentre() {
        assertTrue(CloudDisc.withinDisc(0, 0, 0));
        assertFalse(CloudDisc.withinDisc(1, 0, 0));
        assertFalse(CloudDisc.withinDisc(0, -1, 0));
    }

    @Test
    void textureOffsetWrapsNegativeAnchorsIntoTheUnitRange() {
        // Anchors turn negative once the camera drifts towards negative world coordinates, so the
        // texture coordinate has to wrap instead of going negative.
        assertEquals(3.0F / 8.0F, CloudDisc.textureOffset(-5, 8), 1.0E-7F);
        assertEquals(0.0F, CloudDisc.textureOffset(-8, 8), 1.0E-7F);
        assertEquals(7.0F / 8.0F, CloudDisc.textureOffset(-1, 8), 1.0E-7F);
        assertEquals(1.0F / 8.0F, CloudDisc.textureOffset(9, 8), 1.0E-7F);
        assertEquals(0.0F, CloudDisc.textureOffset(0, 8), 1.0E-7F);
    }

    @Test
    void perspectiveProjectionPixelScaleMatchesTheFieldOfView() {
        final int width = 1280;
        final int height = 720;
        final double fovDegrees = 70.0;
        final Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(fovDegrees),
            (float) width / height,
            0.05F,
            512.0F
        );
        final double expected = height * 0.5 / Math.tan(Math.toRadians(fovDegrees) * 0.5);

        assertEquals(expected, CloudDisc.pixelsPerRadian(height, (float) fovDegrees), EPSILON);
        assertEquals(expected, CloudDisc.pixelsPerRadian(width, height, projection), expected * 1.0E-5);
    }

    @Test
    void projectionsWithoutAPerspectiveDivideReportNoPixelScale() {
        final Matrix4f orthographic = new Matrix4f().ortho(-1.0F, 1.0F, -1.0F, 1.0F, 0.05F, 512.0F);
        final Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(70.0), 1.0F, 0.05F, 512.0F);

        assertEquals(0.0, CloudDisc.pixelsPerRadian(1280, 720, orthographic), EPSILON);
        assertEquals(0.0, CloudDisc.pixelsPerRadian(0, 720, perspective), EPSILON);
        assertEquals(0.0, CloudDisc.pixelsPerRadian(1280, 0, perspective), EPSILON);
        assertEquals(0.0, CloudDisc.pixelsPerRadian(720, 0.0F), EPSILON);
        assertEquals(0.0, CloudDisc.pixelsPerRadian(720, 180.0F), EPSILON);
        assertEquals(0.0, CloudDisc.pixelsPerRadian(720, Float.NaN), EPSILON);
    }

    @Test
    void aMissingProjectionIsRejected() {
        assertThrows(NullPointerException.class, () -> CloudDisc.pixelsPerRadian(1280, 720, null));
    }

    @Test
    void lodCutDistancesOnlyGrowWithThePixelScale() {
        int previousWall = 0;
        int previousPlate = 0;
        for (double pixelsPerRadian = 0.25; pixelsPerRadian < 4096.0; pixelsPerRadian *= 2.0) {
            final int wall = CloudDisc.wallCutCells(pixelsPerRadian, true);
            final int plate = CloudDisc.plateLodCells(pixelsPerRadian, 48.0F, 8.0F, true);
            assertTrue(wall >= previousWall, "wall cut shrank at " + pixelsPerRadian);
            assertTrue(plate >= previousPlate, "plate cut shrank at " + pixelsPerRadian);
            previousWall = wall;
            previousPlate = plate;
        }

        assertEquals(Integer.MAX_VALUE, CloudDisc.wallCutCells(0.0, true));
        assertEquals(Integer.MAX_VALUE, CloudDisc.wallCutCells(-1.0, true));
        assertEquals(Integer.MAX_VALUE, CloudDisc.wallCutCells(512.0, false));
        assertEquals(Integer.MAX_VALUE, CloudDisc.plateLodCells(0.0, 48.0F, 8.0F, true));
        assertEquals(Integer.MAX_VALUE, CloudDisc.plateLodCells(512.0, 48.0F, 8.0F, false));
        // Absurd scales saturate at the disable sentinel instead of overflowing.
        assertEquals(Integer.MAX_VALUE, CloudDisc.wallCutCells(1.0E12, true));
    }

    @Test
    void plateLodKeepsTallCloudsFineForLonger() {
        assertEquals(
            CloudDisc.plateLodCells(512.0, 1.0F, 8.0F, true),
            CloudDisc.plateLodCells(512.0, 0.0F, 8.0F, true)
        );
        assertTrue(
            CloudDisc.plateLodCells(512.0, 96.0F, 8.0F, true)
                > CloudDisc.plateLodCells(512.0, 24.0F, 8.0F, true)
        );
    }

    @Test
    void plateLodMatchesForLayersAboveAndBelowTheCamera() {
        // A deck below the camera subtends the same angle as one above it, so the fine plate radius has to
        // stay identical; a sign leak would collapse the below case onto the one block minimum.
        final float[] heights = {8.0F, 24.0F, 48.0F, 96.0F};
        final double[] scales = {8.0, 64.0, 512.0, 4096.0};
        for (float height : heights) {
            for (double pixelsPerRadian : scales) {
                assertEquals(
                    CloudDisc.plateLodCells(pixelsPerRadian, height, 8.0F, true),
                    CloudDisc.plateLodCells(pixelsPerRadian, -height, 8.0F, true),
                    "height " + height + " at pixel scale " + pixelsPerRadian
                );
            }
        }

        assertTrue(
            CloudDisc.plateLodCells(512.0, -96.0F, 8.0F, true)
                > CloudDisc.plateLodCells(512.0, -24.0F, 8.0F, true),
            "a thicker deck below the camera must keep the fine radius further out"
        );
    }

    @Test
    void driftAngleGrowsWithDriftAndStopsAtAQuarterTurn() {
        final int halfRange = CloudDisc.ALWAYS_DRAWN_CELLS / 2;
        double previous = 0.0;
        for (int drift = 0; drift <= halfRange; drift++) {
            final double angle = CloudDisc.driftAngle(drift);
            assertTrue(angle >= previous, "drift angle shrank at " + drift);
            assertTrue(angle <= Math.PI / 2.0, "drift angle left the first quadrant at " + drift);
            previous = angle;
        }

        assertEquals(0.0, CloudDisc.driftAngle(0), EPSILON);
        assertEquals(Math.PI / 2.0, CloudDisc.driftAngle(halfRange), EPSILON);
        assertEquals(Math.PI / 2.0, CloudDisc.driftAngle(CloudDisc.ALWAYS_DRAWN_CELLS), EPSILON);
    }
}
