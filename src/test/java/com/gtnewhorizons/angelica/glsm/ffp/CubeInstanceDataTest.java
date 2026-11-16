package com.gtnewhorizons.angelica.glsm.ffp;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the instance data the cube layout feeds to the fixed-function shaders: the unit cube vertex
 * stream, the per-cube transform rows, and the texture region written into the instance.
 */
final class CubeInstanceDataTest {

    private static final float EPSILON = 1.0e-5F;

    @Test
    void unitCubeVerticesCoverEveryCornerWithAxialNormals() {
        final ByteBuffer buffer = MemoryUtil.memAlloc(UnitCubeMesh.VERTEX_COUNT * UnitCubeMesh.VERTEX_STRIDE);
        try {
            UnitCubeMesh.write(MemoryUtil.memAddress(buffer));
            final Set<String> corners = new HashSet<>();
            for (int vertex = 0; vertex < UnitCubeMesh.VERTEX_COUNT; vertex++) {
                final long ptr = MemoryUtil.memAddress(buffer) + (long) vertex * UnitCubeMesh.VERTEX_STRIDE;
                final float x = MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_POSITION);
                final float y = MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_POSITION + 4);
                final float z = MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_POSITION + 8);
                assertTrue(x == 0.0F || x == 1.0F, "cube corner x must stay on the unit range");
                assertTrue(y == 0.0F || y == 1.0F, "cube corner y must stay on the unit range");
                assertTrue(z == 0.0F || z == 1.0F, "cube corner z must stay on the unit range");
                corners.add(((int) x) + ":" + ((int) y) + ":" + ((int) z));
            }
            assertEquals(8, corners.size(), "the six faces must share the eight cube corners");
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    @Test
    void eachFaceSharesOneCentreAndOneAxisNormal() {
        final ByteBuffer buffer = MemoryUtil.memAlloc(UnitCubeMesh.VERTEX_COUNT * UnitCubeMesh.VERTEX_STRIDE);
        try {
            UnitCubeMesh.write(MemoryUtil.memAddress(buffer));
            final long base = MemoryUtil.memAddress(buffer);
            for (int face = 0; face < UnitCubeMesh.FACE_COUNT; face++) {
                final long first = base + (long) face * 4 * UnitCubeMesh.VERTEX_STRIDE;
                final float midU = MemoryUtil.memGetFloat(first + UnitCubeMesh.OFFSET_MID);
                final float midV = MemoryUtil.memGetFloat(first + UnitCubeMesh.OFFSET_MID + 8);
                final float nx = MemoryUtil.memGetFloat(first + UnitCubeMesh.OFFSET_NORMAL);
                final float ny = MemoryUtil.memGetFloat(first + UnitCubeMesh.OFFSET_NORMAL + 4);
                final float nz = MemoryUtil.memGetFloat(first + UnitCubeMesh.OFFSET_NORMAL + 8);
                assertEquals(1.0F, Math.abs(nx) + Math.abs(ny) + Math.abs(nz), EPSILON,
                    "a box face normal points along exactly one axis");
                for (int corner = 1; corner < 4; corner++) {
                    final long ptr = first + (long) corner * UnitCubeMesh.VERTEX_STRIDE;
                    assertEquals(midU, MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_MID), EPSILON,
                        "the face centre is shared by its corners");
                    assertEquals(midV, MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_MID + 8), EPSILON,
                        "the face centre is shared by its corners");
                    assertEquals(nx, MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_NORMAL), EPSILON,
                        "the face normal is shared by its corners");
                    assertEquals(ny, MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_NORMAL + 4), EPSILON,
                        "the face normal is shared by its corners");
                    assertEquals(nz, MemoryUtil.memGetFloat(ptr + UnitCubeMesh.OFFSET_NORMAL + 8), EPSILON,
                        "the face normal is shared by its corners");
                }
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    @Test
    void cubeParamsMapTheUnitCubeOntoTheBox() {
        final CubeParams cube = CubeParams.of(64.0F, 64.0F, false, 0, 0, 2.0F, 3.0F, 4.0F, 2, 3, 4, 0.0F);
        assertNotNull(cube, "a non-degenerate box must produce cube parameters");
        final ByteBuffer instance = MemoryUtil.memAlloc(CubeInstancedAttribs.STRIDE);
        try {
            final float[] identity = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
            final long base = MemoryUtil.memAddress(instance);
            cube.writeRows(base, identity, 0, 1.0F);
            final Set<String> mapped = new HashSet<>();
            for (int corner = 0; corner < 8; corner++) {
                final float x = (corner & 1);
                final float y = ((corner >> 1) & 1);
                final float z = ((corner >> 2) & 1);
                final float wx = rowDot(base, 0, x, y, z);
                final float wy = rowDot(base, 1, x, y, z);
                final float wz = rowDot(base, 2, x, y, z);
                assertTrue(wx >= 2.0F - EPSILON && wx <= 4.0F + EPSILON, "x stays inside the box");
                assertTrue(wy >= 3.0F - EPSILON && wy <= 6.0F + EPSILON, "y stays inside the box");
                assertTrue(wz >= 4.0F - EPSILON && wz <= 8.0F + EPSILON, "z stays inside the box");
                mapped.add(Math.round(wx) + ":" + Math.round(wy) + ":" + Math.round(wz));
            }
            assertEquals(8, mapped.size(), "the eight unit corners must map to eight distinct box corners");
        } finally {
            MemoryUtil.memFree(instance);
        }
    }

    @Test
    void cubeTextureUsesTheLightmapRegionScale() {
        final CubeParams cube = CubeParams.of(64.0F, 64.0F, false, 8, 16, 0.0F, 0.0F, 0.0F, 4, 4, 4, 0.0F);
        assertNotNull(cube);
        final ByteBuffer instance = MemoryUtil.memAlloc(CubeInstancedAttribs.STRIDE);
        try {
            final long base = MemoryUtil.memAddress(instance);
            cube.writeTexture(base, 0.25F, 0.5F, 0.5F, 0.5F);
            final float u0 = MemoryUtil.memGetFloat(base + CubeInstancedAttribs.OFFSET_CUBE_TEX);
            final float v0 = MemoryUtil.memGetFloat(base + CubeInstancedAttribs.OFFSET_CUBE_TEX + 4);
            assertEquals(0.25F + 8.0F / 64.0F * 0.5F, u0, EPSILON, "the texture origin follows the region");
            assertEquals(0.5F + 16.0F / 64.0F * 0.5F, v0, EPSILON, "the texture origin follows the region");
            final float scaleZ = MemoryUtil.memGetFloat(base + CubeInstancedAttribs.OFFSET_LIGHTMAP_SCALE + 8);
            assertEquals(4.0F / 64.0F * 0.5F, scaleZ, EPSILON, "the depth texture scale follows the region width");
        } finally {
            MemoryUtil.memFree(instance);
        }
    }

    private static float rowDot(long base, int row, float x, float y, float z) {
        final long ptr = base + InstancedAttribs.OFFSET_ROWS + row * 16L;
        return MemoryUtil.memGetFloat(ptr) * x
            + MemoryUtil.memGetFloat(ptr + 4) * y
            + MemoryUtil.memGetFloat(ptr + 8) * z
            + MemoryUtil.memGetFloat(ptr + 12);
    }
}
