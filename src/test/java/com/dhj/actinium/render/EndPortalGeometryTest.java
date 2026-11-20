package com.dhj.actinium.render;

import net.minecraft.util.EnumFacing;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndPortalGeometryTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void homogeneousPathEmitsFourOutwardFacingVerticesForEveryVisibleFace() {
        List<EndPortalMesh.FaceQuad> faces = EndPortalMesh.visibleFaces(
            EnumSet.allOf(EnumFacing.class)::contains,
            0.75F
        );
        List<ProjectiveVertex> vertices = homogeneousVertices(faces, 10.0D, 20.0D, 30.0D);

        assertEquals(24, vertices.size());
        for (EnumFacing face : EnumFacing.values()) {
            List<ProjectiveVertex> faceVertices = vertices.stream().filter(vertex -> vertex.face == face).toList();
            assertEquals(4, faceVertices.size(), face.getName());
            assertOutward(faceVertices, face);
        }
    }

    @Test
    void visibleFaceGeometryAppliesRendererOffsetOnlyToTheTopFace() {
        List<EndPortalMesh.FaceQuad> faces = EndPortalMesh.visibleFaces(
            EnumSet.of(EnumFacing.UP, EnumFacing.DOWN)::contains,
            0.625F
        );
        List<ProjectiveVertex> vertices = homogeneousVertices(faces, 10.0D, 20.0D, 30.0D);

        List<ProjectiveVertex> top = vertices.stream().filter(vertex -> vertex.face == EnumFacing.UP).toList();
        List<ProjectiveVertex> bottom = vertices.stream().filter(vertex -> vertex.face == EnumFacing.DOWN).toList();
        assertTrue(top.stream().allMatch(vertex -> Math.abs(vertex.y - 20.625D) < EPSILON));
        assertTrue(bottom.stream().allMatch(vertex -> Math.abs(vertex.y - 20.0D) < EPSILON));
    }

    @Test
    void homogeneousPathPreservesLayerColorFullbrightNormalAndQ() {
        List<EndPortalMesh.FaceQuad> faces = EndPortalMesh.visibleFaces(face -> face == EnumFacing.UP, 0.75F);
        EndPortalLayers.Layer layer = EndPortalLayers.create(1, 0.35F).getFirst();
        List<ProjectiveVertex> vertices = new ArrayList<>();
        EndPortalGeometry.emitHomogeneous(
            faces,
            new EndPortalProjection(new Matrix4f(), new Matrix4f()),
            10.0D,
            20.0D,
            30.0D,
            layer,
            (face, x, y, z, s, t, r, q, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) ->
                vertices.add(new ProjectiveVertex(
                    face, x, y, z, s, t, r, q, red, green, blue, alpha,
                    lightU, lightV, normalX, normalY, normalZ
                ))
        );

        for (ProjectiveVertex vertex : vertices) {
            assertEquals(layer.red(), vertex.red);
            assertEquals(layer.green(), vertex.green);
            assertEquals(layer.blue(), vertex.blue);
            assertEquals(1.0F, vertex.alpha);
            assertEquals(240, vertex.lightU);
            assertEquals(240, vertex.lightV);
            assertEquals(1.0F, vertex.q);
            assertEquals(1.0F, vertex.normalY);
        }
    }

    @Test
    void shaderPathEmitsFiniteDividedTriangleVertices() {
        Matrix4f perspective = new Matrix4f().perspective(
            (float) Math.toRadians(70.0D),
            16.0F / 9.0F,
            0.05F,
            256.0F
        );
        EndPortalProjection projection = new EndPortalProjection(perspective, new Matrix4f());
        EndPortalLayers.Layer layer = EndPortalLayers.create(1, 0.25F).getFirst();
        List<EndPortalMesh.FaceQuad> faces = EndPortalMesh.visibleFaces(face -> face == EnumFacing.UP, 0.75F);
        List<EndPortalMesh.Triangle> triangles = EndPortalMesh.shaderTriangles(
            faces,
            projection,
            0.0D,
            0.0D,
            -4.0D,
            List.of(layer),
            true
        );
        List<DividedVertex> vertices = new ArrayList<>();

        EndPortalGeometry.emitDivided(
            triangles,
            layer,
            0.0D,
            0.0D,
            -4.0D,
            (face, x, y, z, u, v, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) ->
                vertices.add(new DividedVertex(u, v, lightU, lightV, normalY))
        );

        assertEquals(triangles.size() * 3, vertices.size());
        assertTrue(vertices.stream().allMatch(vertex -> Float.isFinite(vertex.u) && Float.isFinite(vertex.v)));
        assertTrue(vertices.stream().allMatch(vertex -> vertex.lightU == 240 && vertex.lightV == 240));
        assertTrue(vertices.stream().allMatch(vertex -> vertex.normalY == 1.0F));
    }

    @Test
    void compositePathMapsClipSpaceToNormalizedScreenCoordinates() {
        Matrix4f perspective = new Matrix4f().perspective(
            (float) Math.toRadians(70.0D),
            16.0F / 9.0F,
            0.05F,
            256.0F
        );
        EndPortalProjection projection = new EndPortalProjection(perspective, new Matrix4f());
        List<EndPortalLayers.Layer> layers = EndPortalLayers.create(16, 0.25F);
        List<EndPortalMesh.FaceQuad> faces = EndPortalMesh.visibleFaces(face -> face == EnumFacing.UP, 0.75F);
        List<EndPortalMesh.Triangle> triangles = EndPortalMesh.shaderTriangles(
            faces,
            projection,
            0.0D,
            0.0D,
            -4.0D,
            layers,
            true
        );
        List<DividedVertex> vertices = new ArrayList<>();

        EndPortalGeometry.emitComposite(
            triangles,
            0.0D,
            0.0D,
            -4.0D,
            (face, x, y, z, u, v, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) ->
                vertices.add(new DividedVertex(u, v, lightU, lightV, normalY))
        );

        assertEquals(triangles.size() * 3, vertices.size());
        int vertexIndex = 0;
        for (EndPortalMesh.Triangle triangle : triangles) {
            for (EndPortalMesh.MeshVertex meshVertex : List.of(
                triangle.first(), triangle.second(), triangle.third()
            )) {
                EndPortalProjection.ClipPosition clip = meshVertex.clip();
                DividedVertex vertex = vertices.get(vertexIndex++);
                assertEquals(clip.x() / clip.w() * 0.5D + 0.5D, vertex.u, EPSILON);
                assertEquals(clip.y() / clip.w() * 0.5D + 0.5D, vertex.v, EPSILON);
                assertEquals(240, vertex.lightU);
                assertEquals(240, vertex.lightV);
                assertEquals(1.0F, vertex.normalY);
            }
        }
    }

    private static List<ProjectiveVertex> homogeneousVertices(
        List<EndPortalMesh.FaceQuad> faces,
        double x,
        double y,
        double z
    ) {
        List<ProjectiveVertex> vertices = new ArrayList<>();
        EndPortalGeometry.emitHomogeneous(
            faces,
            new EndPortalProjection(new Matrix4f(), new Matrix4f()),
            x,
            y,
            z,
            EndPortalLayers.create(1, 0.0F).getFirst(),
            (face, vertexX, vertexY, vertexZ, s, t, r, q, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) ->
                vertices.add(new ProjectiveVertex(
                    face, vertexX, vertexY, vertexZ, s, t, r, q, red, green, blue, alpha,
                    lightU, lightV, normalX, normalY, normalZ
                ))
        );
        return vertices;
    }

    private static void assertOutward(List<ProjectiveVertex> vertices, EnumFacing face) {
        ProjectiveVertex first = vertices.get(0);
        ProjectiveVertex second = vertices.get(1);
        ProjectiveVertex third = vertices.get(2);
        double edgeAx = second.x - first.x;
        double edgeAy = second.y - first.y;
        double edgeAz = second.z - first.z;
        double edgeBx = third.x - first.x;
        double edgeBy = third.y - first.y;
        double edgeBz = third.z - first.z;
        double crossX = edgeAy * edgeBz - edgeAz * edgeBy;
        double crossY = edgeAz * edgeBx - edgeAx * edgeBz;
        double crossZ = edgeAx * edgeBy - edgeAy * edgeBx;
        assertTrue(
            crossX * face.getXOffset() + crossY * face.getYOffset() + crossZ * face.getZOffset() > 0.0D,
            face.getName()
        );
    }

    private record DividedVertex(float u, float v, int lightU, int lightV, float normalY) {
    }

    private record ProjectiveVertex(
        EnumFacing face,
        double x,
        double y,
        double z,
        float s,
        float t,
        float r,
        float q,
        float red,
        float green,
        float blue,
        float alpha,
        int lightU,
        int lightV,
        float normalX,
        float normalY,
        float normalZ
    ) {
    }
}
