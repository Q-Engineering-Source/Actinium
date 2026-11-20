package com.dhj.actinium.render;

import net.minecraft.util.EnumFacing;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndPortalMeshTest {
    private static final Matrix4f PERSPECTIVE = new Matrix4f().perspective(
        (float) Math.toRadians(70.0D),
        16.0F / 9.0F,
        0.05F,
        256.0F
    );

    @Test
    void nearPortalUsesAtLeastAsMuchTopologyAsFarPortal() {
        EndPortalProjection projection = new EndPortalProjection(PERSPECTIVE, new Matrix4f());
        EndPortalLayers.Layer layer = EndPortalLayers.create(1, 0.25F).getFirst();
        List<EndPortalMesh.FaceQuad> face = EndPortalMesh.visibleFaces(direction -> direction == EnumFacing.UP, 0.75F);

        List<EndPortalMesh.Triangle> near = EndPortalMesh.shaderTriangles(
            face, projection, 0.0D, 0.0D, -2.0D, List.of(layer), true
        );
        List<EndPortalMesh.Triangle> far = EndPortalMesh.shaderTriangles(
            face, projection, 0.0D, 0.0D, -32.0D, List.of(layer), true
        );

        assertTrue(near.size() >= far.size());
        assertTrue(near.size() <= EndPortalMesh.MAXIMUM_CLIPPED_TRIANGLES_PER_FACE);
    }

    @Test
    void stopsBuildingPortalTrianglesWhenTheFaceLeavesTheRightEdge() {
        var faces = EndPortalMesh.visibleFaces(direction -> direction == EnumFacing.UP, 0.75F);
        var layers = EndPortalLayers.create(9, 0.25F);
        var crossing = new EndPortalProjection(PERSPECTIVE, new Matrix4f().rotateY((float) Math.toRadians(-49)));
        var offscreen = new EndPortalProjection(PERSPECTIVE, new Matrix4f().rotateY((float) Math.toRadians(-53)));

        var crossingTriangles = EndPortalMesh.shaderTriangles(faces, crossing, 0.0D, -0.75D, -12.0D, layers, true);
        assertFalse(crossingTriangles.isEmpty());
        assertEquals(EndPortalMesh.shaderTriangles(faces, crossing, 0.0D, -0.75D, -12.0D, layers, false), crossingTriangles);

        var hiddenTriangles = EndPortalMesh.shaderTriangles(faces, offscreen, 0.0D, -0.75D, -12.0D, layers, true);
        assertTrue(hiddenTriangles.isEmpty(), () -> "Offscreen portal emitted " + hiddenTriangles.size() + " triangles");
        assertFalse(EndPortalMesh.shaderTriangles(faces, offscreen, 0.0D, -0.75D, -12.0D, layers, false).isEmpty());
    }

    @Test
    void ordinaryVisibleTrianglesStayWithinQuarterTexelError() {
        EndPortalProjection projection = new EndPortalProjection(PERSPECTIVE, new Matrix4f().rotateX(0.35F));
        EndPortalLayers.Layer layer = EndPortalLayers.create(1, 0.25F).getFirst();
        List<EndPortalMesh.FaceQuad> face = EndPortalMesh.visibleFaces(direction -> direction == EnumFacing.UP, 0.75F);
        List<EndPortalMesh.Triangle> triangles = EndPortalMesh.shaderTriangles(
            face,
            projection,
            0.0D,
            0.0D,
            -8.0D,
            List.of(layer),
            true
        );

        assertFalse(triangles.isEmpty());
        for (EndPortalMesh.Triangle triangle : triangles) {
            double error = EndPortalMesh.interpolationError(
                layer,
                triangle.first().clip(),
                triangle.second().clip(),
                triangle.third().clip()
            );
            assertTrue(error <= EndPortalMesh.MAXIMUM_UV_ERROR, "error=" + error);
        }
    }

    @Test
    void ordinaryNearPortalKeepsEveryLayerWithinQuarterTexelError() {
        EndPortalProjection projection = new EndPortalProjection(PERSPECTIVE, new Matrix4f().rotateX(0.35F));
        List<EndPortalLayers.Layer> layers = EndPortalLayers.create(16, 0.25F);
        List<EndPortalMesh.FaceQuad> face = EndPortalMesh.visibleFaces(direction -> direction == EnumFacing.UP, 0.75F);

        List<EndPortalMesh.Triangle> triangles = EndPortalMesh.shaderTriangles(
            face,
            projection,
            0.0D,
            0.0D,
            -4.0D,
            layers,
            true
        );

        assertFalse(triangles.isEmpty());
        assertTrue(triangles.size() <= EndPortalMesh.MAXIMUM_CLIPPED_TRIANGLES_PER_FACE);
        for (EndPortalLayers.Layer layer : layers) {
            for (EndPortalMesh.Triangle triangle : triangles) {
                double error = EndPortalMesh.interpolationError(
                    layer,
                    triangle.first().clip(),
                    triangle.second().clip(),
                    triangle.third().clip()
                );
                assertTrue(error <= EndPortalMesh.MAXIMUM_UV_ERROR, "error=" + error);
            }
        }
    }

    @Test
    void maximumDepthHasAStaticTriangleBudget() {
        assertEquals(4, EndPortalMesh.MAXIMUM_SUBDIVISION_DEPTH);
        assertEquals(512, EndPortalMesh.MAXIMUM_SOURCE_TRIANGLES_PER_FACE);
        assertEquals(1536, EndPortalMesh.MAXIMUM_CLIPPED_TRIANGLES_PER_FACE);
    }

    @Test
    void laterLayerCanRequireDeeperSubdivisionThanTheFirstLayer() {
        EndPortalProjection projection = new EndPortalProjection(PERSPECTIVE, new Matrix4f().rotateX(0.35F));
        EndPortalLayers.Layer first = testLayer(0.0F);
        EndPortalLayers.Layer later = testLayer(64.0F);
        List<EndPortalMesh.FaceQuad> face = EndPortalMesh.visibleFaces(direction -> direction == EnumFacing.UP, 0.75F);

        List<EndPortalMesh.Triangle> firstOnly = EndPortalMesh.shaderTriangles(
            face, projection, 0.0D, 0.0D, -8.0D, List.of(first), true
        );
        List<EndPortalMesh.Triangle> allLayers = EndPortalMesh.shaderTriangles(
            face, projection, 0.0D, 0.0D, -8.0D, List.of(first, later), true
        );

        assertEquals(2, firstOnly.size());
        assertTrue(allLayers.size() > firstOnly.size());
    }

    @Test
    void nearPlaneCrossingIsClippedWithoutClampingQ() {
        EndPortalProjection projection = new EndPortalProjection(PERSPECTIVE, new Matrix4f());
        EndPortalLayers.Layer layer = EndPortalLayers.create(1, 0.25F).getFirst();
        List<EndPortalMesh.FaceQuad> face = EndPortalMesh.visibleFaces(direction -> direction == EnumFacing.UP, 0.75F);
        List<EndPortalMesh.Triangle> triangles = EndPortalMesh.shaderTriangles(
            face,
            projection,
            0.0D,
            -0.75D,
            -0.5D,
            List.of(layer),
            true
        );

        assertFalse(triangles.isEmpty());
        for (EndPortalMesh.Triangle triangle : triangles) {
            for (EndPortalMesh.MeshVertex vertex : List.of(triangle.first(), triangle.second(), triangle.third())) {
                assertTrue(vertex.clip().z() + vertex.clip().w() >= -1.0E-9D);
                assertTrue(vertex.clip().w() >= EndPortalMesh.MINIMUM_CLIP_W - 1.0E-9D);
            }
        }
    }

    private static EndPortalLayers.Layer testLayer(float scale) {
        return new EndPortalLayers.Layer(
            EndPortalLayers.Texture.END_PORTAL,
            EndPortalLayers.Blend.ADDITIVE,
            1.0F,
            1.0F,
            1.0F,
            0.0F,
            0.0F,
            scale,
            0.0F,
            0.0F,
            scale
        );
    }
}
