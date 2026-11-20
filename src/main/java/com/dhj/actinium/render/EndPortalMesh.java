package com.dhj.actinium.render;

import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Builds visible portal faces and the clipped adaptive triangle mesh used by shader-pack rendering.
 */
final class EndPortalMesh {
    static final double MINIMUM_CLIP_W = 1.0E-4D;
    // Vanilla end_portal.png is 256x256; end_sky.png is 128x128.
    static final int MAXIMUM_TEXTURE_RESOLUTION = 256;
    static final double MAXIMUM_UV_ERROR = 0.25D / MAXIMUM_TEXTURE_RESOLUTION;
    static final int MAXIMUM_SUBDIVISION_DEPTH = 4;
    static final int MAXIMUM_SOURCE_TRIANGLES_PER_FACE = 2 << (MAXIMUM_SUBDIVISION_DEPTH * 2);
    // Clipping one triangle against two planes can produce at most a five-vertex polygon.
    static final int MAXIMUM_CLIPPED_TRIANGLES_PER_FACE = MAXIMUM_SOURCE_TRIANGLES_PER_FACE * 3;
    private static final int LEFT = 1;
    private static final int RIGHT = 1 << 1;
    private static final int BOTTOM = 1 << 2;
    private static final int TOP = 1 << 3;

    private EndPortalMesh() {
    }

    /**
     * Creates the visible face quads with the vanilla winding and renderer-specific top offset.
     *
     * @param shouldRenderFace visible-face predicate from the block entity
     * @param topOffset renderer-specific top face height
     * @return immutable visible face list
     */
    static List<FaceQuad> visibleFaces(Predicate<EnumFacing> shouldRenderFace, float topOffset) {
        List<FaceQuad> faces = new ArrayList<>(6);
        add(faces, shouldRenderFace, EnumFacing.SOUTH,
            point(0.0D, 0.0D, 1.0D), point(1.0D, 0.0D, 1.0D),
            point(1.0D, 1.0D, 1.0D), point(0.0D, 1.0D, 1.0D));
        add(faces, shouldRenderFace, EnumFacing.NORTH,
            point(0.0D, 1.0D, 0.0D), point(1.0D, 1.0D, 0.0D),
            point(1.0D, 0.0D, 0.0D), point(0.0D, 0.0D, 0.0D));
        add(faces, shouldRenderFace, EnumFacing.EAST,
            point(1.0D, 1.0D, 0.0D), point(1.0D, 1.0D, 1.0D),
            point(1.0D, 0.0D, 1.0D), point(1.0D, 0.0D, 0.0D));
        add(faces, shouldRenderFace, EnumFacing.WEST,
            point(0.0D, 0.0D, 0.0D), point(0.0D, 0.0D, 1.0D),
            point(0.0D, 1.0D, 1.0D), point(0.0D, 1.0D, 0.0D));
        add(faces, shouldRenderFace, EnumFacing.DOWN,
            point(0.0D, 0.0D, 0.0D), point(1.0D, 0.0D, 0.0D),
            point(1.0D, 0.0D, 1.0D), point(0.0D, 0.0D, 1.0D));
        add(faces, shouldRenderFace, EnumFacing.UP,
            point(0.0D, topOffset, 1.0D), point(1.0D, topOffset, 1.0D),
            point(1.0D, topOffset, 0.0D), point(0.0D, topOffset, 0.0D));
        return List.copyOf(faces);
    }

    /**
     * Tessellates and clips all visible faces using one topology derived from the maximum-scale layer.
     *
     * @param faces visible face quads
     * @param projection active draw projection
     * @param renderX render-relative block X
     * @param renderY render-relative block Y
     * @param renderZ render-relative block Z
     * @param layers all layers whose rotated projective error must be bounded
     * @param cullOffscreenFaces whether the active projection bounds the portal's draw
     * @return immutable clipped triangles
     */
    static List<Triangle> shaderTriangles(
        List<FaceQuad> faces,
        EndPortalProjection projection,
        double renderX,
        double renderY,
        double renderZ,
        List<EndPortalLayers.Layer> layers,
        boolean cullOffscreenFaces
    ) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("layers must not be empty");
        }
        List<Triangle> triangles = new ArrayList<>();
        for (FaceQuad face : faces) {
            if (cullOffscreenFaces && outsideSidePlanes(face, projection, renderX, renderY, renderZ)) {
                continue;
            }
            int depth = subdivisionDepth(face, projection, renderX, renderY, renderZ, layers);
            int divisions = 1 << depth;
            for (int row = 0; row < divisions; row++) {
                double v0 = (double) row / divisions;
                double v1 = (double) (row + 1) / divisions;
                for (int column = 0; column < divisions; column++) {
                    double u0 = (double) column / divisions;
                    double u1 = (double) (column + 1) / divisions;
                    MeshVertex p00 = vertex(face, projection, renderX, renderY, renderZ, u0, v0);
                    MeshVertex p10 = vertex(face, projection, renderX, renderY, renderZ, u1, v0);
                    MeshVertex p11 = vertex(face, projection, renderX, renderY, renderZ, u1, v1);
                    MeshVertex p01 = vertex(face, projection, renderX, renderY, renderZ, u0, v1);
                    clipTriangle(triangles, face.direction(), p00, p10, p11);
                    clipTriangle(triangles, face.direction(), p00, p11, p01);
                }
            }
        }
        return List.copyOf(triangles);
    }

    /** A face can be rejected only when all four corners lie outside the same side plane. */
    private static boolean outsideSidePlanes(FaceQuad face, EndPortalProjection projection,
                                             double x, double y, double z) {
        int outside = sideMask(face.first(), projection, x, y, z);
        outside &= sideMask(face.second(), projection, x, y, z);
        outside &= sideMask(face.third(), projection, x, y, z);
        outside &= sideMask(face.fourth(), projection, x, y, z);
        return outside != 0;
    }

    private static int sideMask(LocalPosition corner, EndPortalProjection projection, double x, double y, double z) {
        var clip = projection.clip(x + corner.x(), y + corner.y(), z + corner.z());
        double w = clip.w();
        return (clip.x() < -w ? LEFT : 0) | (clip.x() > w ? RIGHT : 0)
            | (clip.y() < -w ? BOTTOM : 0) | (clip.y() > w ? TOP : 0);
    }

    private static int subdivisionDepth(
        FaceQuad face,
        EndPortalProjection projection,
        double renderX,
        double renderY,
        double renderZ,
        List<EndPortalLayers.Layer> layers
    ) {
        for (int depth = 0; depth < MAXIMUM_SUBDIVISION_DEPTH; depth++) {
            if (maximumError(face, projection, renderX, renderY, renderZ, layers, 1 << depth) <= MAXIMUM_UV_ERROR) {
                return depth;
            }
        }
        return MAXIMUM_SUBDIVISION_DEPTH;
    }

    private static double maximumError(
        FaceQuad face,
        EndPortalProjection projection,
        double renderX,
        double renderY,
        double renderZ,
        List<EndPortalLayers.Layer> layers,
        int divisions
    ) {
        double maximum = 0.0D;
        for (int row = 0; row < divisions; row++) {
            double v0 = (double) row / divisions;
            double v1 = (double) (row + 1) / divisions;
            for (int column = 0; column < divisions; column++) {
                double u0 = (double) column / divisions;
                double u1 = (double) (column + 1) / divisions;
                MeshVertex p00 = vertex(face, projection, renderX, renderY, renderZ, u0, v0);
                MeshVertex p10 = vertex(face, projection, renderX, renderY, renderZ, u1, v0);
                MeshVertex p11 = vertex(face, projection, renderX, renderY, renderZ, u1, v1);
                MeshVertex p01 = vertex(face, projection, renderX, renderY, renderZ, u0, v1);
                if (!insideClipVolume(p00.clip()) || !insideClipVolume(p10.clip())
                    || !insideClipVolume(p11.clip()) || !insideClipVolume(p01.clip())) {
                    return Double.POSITIVE_INFINITY;
                }
                maximum = Math.max(maximum, maximumLayerError(layers, p00.clip(), p10.clip(), p11.clip()));
                maximum = Math.max(maximum, maximumLayerError(layers, p00.clip(), p11.clip(), p01.clip()));
            }
        }
        return maximum;
    }

    private static double maximumLayerError(
        List<EndPortalLayers.Layer> layers,
        EndPortalProjection.ClipPosition first,
        EndPortalProjection.ClipPosition second,
        EndPortalProjection.ClipPosition third
    ) {
        double maximum = 0.0D;
        for (EndPortalLayers.Layer layer : layers) {
            maximum = Math.max(maximum, interpolationError(layer, first, second, third));
        }
        return maximum;
    }

    static double interpolationError(
        EndPortalLayers.Layer layer,
        EndPortalProjection.ClipPosition first,
        EndPortalProjection.ClipPosition second,
        EndPortalProjection.ClipPosition third
    ) {
        EndPortalProjection.ProjectiveTexCoord a = layer.projective(first);
        EndPortalProjection.ProjectiveTexCoord b = layer.projective(second);
        EndPortalProjection.ProjectiveTexCoord c = layer.projective(third);
        double maximum = Math.max(edgeError(a, b), Math.max(edgeError(b, c), edgeError(c, a)));
        double targetU = (a.dividedU() + b.dividedU() + c.dividedU()) / 3.0D;
        double targetV = (a.dividedV() + b.dividedV() + c.dividedV()) / 3.0D;
        double denominator = 1.0D / a.q() + 1.0D / b.q() + 1.0D / c.q();
        double interpolatedU = (a.dividedU() / a.q() + b.dividedU() / b.q() + c.dividedU() / c.q()) / denominator;
        double interpolatedV = (a.dividedV() / a.q() + b.dividedV() / b.q() + c.dividedV() / c.q()) / denominator;
        return Math.max(maximum, Math.max(Math.abs(targetU - interpolatedU), Math.abs(targetV - interpolatedV)));
    }

    private static double edgeError(
        EndPortalProjection.ProjectiveTexCoord first,
        EndPortalProjection.ProjectiveTexCoord second
    ) {
        double targetU = (first.dividedU() + second.dividedU()) * 0.5D;
        double targetV = (first.dividedV() + second.dividedV()) * 0.5D;
        double denominator = 1.0D / first.q() + 1.0D / second.q();
        double interpolatedU = (first.dividedU() / first.q() + second.dividedU() / second.q()) / denominator;
        double interpolatedV = (first.dividedV() / first.q() + second.dividedV() / second.q()) / denominator;
        return Math.max(Math.abs(targetU - interpolatedU), Math.abs(targetV - interpolatedV));
    }

    private static void clipTriangle(
        List<Triangle> output,
        EnumFacing face,
        MeshVertex first,
        MeshVertex second,
        MeshVertex third
    ) {
        List<MeshVertex> polygon = new ArrayList<>(List.of(first, second, third));
        polygon = clip(polygon, vertex -> vertex.clip().z() + vertex.clip().w());
        polygon = clip(polygon, vertex -> vertex.clip().w() - MINIMUM_CLIP_W);
        if (polygon.size() < 3) {
            return;
        }
        MeshVertex origin = polygon.getFirst();
        for (int index = 1; index < polygon.size() - 1; index++) {
            output.add(new Triangle(face, origin, polygon.get(index), polygon.get(index + 1)));
        }
    }

    private static List<MeshVertex> clip(List<MeshVertex> input, ToDoubleFunction<MeshVertex> plane) {
        if (input.isEmpty()) {
            return input;
        }
        List<MeshVertex> output = new ArrayList<>(input.size() + 1);
        MeshVertex previous = input.getLast();
        double previousDistance = plane.applyAsDouble(previous);
        boolean previousInside = previousDistance >= 0.0D;
        for (MeshVertex current : input) {
            double currentDistance = plane.applyAsDouble(current);
            boolean currentInside = currentDistance >= 0.0D;
            if (currentInside != previousInside) {
                double amount = previousDistance / (previousDistance - currentDistance);
                output.add(previous.interpolate(current, amount));
            }
            if (currentInside) {
                output.add(current);
            }
            previous = current;
            previousDistance = currentDistance;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean insideClipVolume(EndPortalProjection.ClipPosition clip) {
        return clip.z() + clip.w() >= 0.0D && clip.w() >= MINIMUM_CLIP_W;
    }

    private static MeshVertex vertex(
        FaceQuad face,
        EndPortalProjection projection,
        double renderX,
        double renderY,
        double renderZ,
        double u,
        double v
    ) {
        LocalPosition local = face.interpolate(u, v);
        return new MeshVertex(
            local,
            projection.clip(renderX + local.x(), renderY + local.y(), renderZ + local.z())
        );
    }

    private static void add(
        List<FaceQuad> faces,
        Predicate<EnumFacing> shouldRenderFace,
        EnumFacing direction,
        LocalPosition first,
        LocalPosition second,
        LocalPosition third,
        LocalPosition fourth
    ) {
        if (shouldRenderFace.test(direction)) {
            faces.add(new FaceQuad(direction, first, second, third, fourth));
        }
    }

    private static LocalPosition point(double x, double y, double z) {
        return new LocalPosition(x, y, z);
    }

    /** Visible quad in vanilla winding order. */
    record FaceQuad(
        EnumFacing direction,
        LocalPosition first,
        LocalPosition second,
        LocalPosition third,
        LocalPosition fourth
    ) {
        LocalPosition interpolate(double u, double v) {
            return LocalPosition.interpolate(first, second, third, fourth, u, v);
        }
    }

    /** Local block-space position. */
    record LocalPosition(double x, double y, double z) {
        static LocalPosition interpolate(
            LocalPosition first,
            LocalPosition second,
            LocalPosition third,
            LocalPosition fourth,
            double u,
            double v
        ) {
            double firstWeight = (1.0D - u) * (1.0D - v);
            double secondWeight = u * (1.0D - v);
            double thirdWeight = u * v;
            double fourthWeight = (1.0D - u) * v;
            return new LocalPosition(
                first.x * firstWeight + second.x * secondWeight + third.x * thirdWeight + fourth.x * fourthWeight,
                first.y * firstWeight + second.y * secondWeight + third.y * thirdWeight + fourth.y * fourthWeight,
                first.z * firstWeight + second.z * secondWeight + third.z * thirdWeight + fourth.z * fourthWeight
            );
        }

        LocalPosition interpolate(LocalPosition other, double amount) {
            return new LocalPosition(
                x + (other.x - x) * amount,
                y + (other.y - y) * amount,
                z + (other.z - z) * amount
            );
        }
    }

    /** Local and clip coordinates retained through homogeneous clipping. */
    record MeshVertex(LocalPosition local, EndPortalProjection.ClipPosition clip) {
        MeshVertex interpolate(MeshVertex other, double amount) {
            return new MeshVertex(
                local.interpolate(other.local, amount),
                new EndPortalProjection.ClipPosition(
                    clip.x() + (other.clip.x() - clip.x()) * amount,
                    clip.y() + (other.clip.y() - clip.y()) * amount,
                    clip.z() + (other.clip.z() - clip.z()) * amount,
                    clip.w() + (other.clip.w() - clip.w()) * amount
                )
            );
        }
    }

    /** Clipped shader triangle with a stable outward face normal. */
    record Triangle(EnumFacing face, MeshVertex first, MeshVertex second, MeshVertex third) {
    }
}
