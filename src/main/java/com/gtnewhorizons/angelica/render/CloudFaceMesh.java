package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import static com.gtnewhorizons.angelica.render.CloudDisc.ALWAYS_DRAWN_CELLS;
import static com.gtnewhorizons.angelica.render.CloudDisc.MARGIN_CELLS;
import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGE_COUNT;
import static com.gtnewhorizons.angelica.render.CloudDisc.wedgeOf;

/**
 * Cloud walls as one instanced face record each.
 *
 * <p>Every exposed cell side becomes a 4 byte record holding the cell, the direction and two flag bits,
 * and one instance of a four index quad expands it in the vertex shader. The records are grouped by wedge
 * and by ring distance so the renderer can draw one angular arc at a time, and the six faces of the cell
 * the camera sits in are stamped on every reorder so the inside of the deck never opens up.
 */
final class CloudFaceMesh {

    /** Direction indices of the six cell faces, matching the corner and shade tables in the face shader. */
    static final int DIR_DOWN = 0, DIR_UP = 1, DIR_NORTH = 2, DIR_SOUTH = 3, DIR_WEST = 4, DIR_EAST = 5;
    /** Largest cell coordinate the packed face record can hold in the ten bit fields. */
    static final int CELL_LIMIT = 511;
    /** Marks the interior faces whose winding has to be flipped so they face into the cell. */
    private static final int FLAG_INSIDE = 1;
    /** Instance attribute the packed face record is bound to. */
    private static final int FACE_ATTRIB = 0;
    /** cellX 10b | cellZ 10b | dir 3b | flags 2b. */
    private static final int FACE_BYTES = 4;
    /** Number of faces of the single cell the camera occupies. */
    private static final int INTERIOR_FACES = 6;
    /** Buckets are the always drawn group plus one per wedge. */
    private static final int BUCKET_COUNT = WEDGE_COUNT + 1;
    /** Bucket of faces close enough to the camera to skip wedge culling. */
    private static final int BUCKET_ALWAYS_DRAWN = 0;
    /** First bucket of the per wedge face groups. */
    private static final int BUCKET_WALL_WEDGE = 1;
    /** Start index of each wedge in the published draw order. */
    private final int[] wallStart = new int[WEDGE_COUNT + 1];
    /** Start index of each wedge for the plate ranges, unused by this mesh but kept for a shared interface. */
    private final int[] plateStart = new int[WEDGE_COUNT + 1];
    /** Vertex array holding the instance attribute and the shared quad index buffer. */
    private int vao = -1;
    /** Instance buffer with the packed face records. */
    private int vbo = -1;
    /** Quad index buffer shared by every draw of this mesh. */
    private int ebo = -1;
    /** Capacity of the instance buffer in bytes, used to choose between a resize and a sub upload. */
    private int vboCapacityBytes;
    /** Faces currently uploaded, excluding the interior faces appended after them. */
    private int uploadedCount;
    /** Faces stamped by the last build, before the interior faces are appended. */
    private int stampedCount;
    /** Number of interior faces appended after the wall faces. */
    private int interiorCount;
    /** Packed face records built for this anchor. */
    private int[] faceData = new int[4096];
    /** Sort key of each stamped face, used by the counting sort. */
    private int[] faceSortKey = new int[4096];
    /** Prefix offsets of the sort keys, reused across builds. */
    private int[] sortKeyStart = new int[0];
    /** Rings per bucket, the ring resolution the sort keys are quantized to. */
    private int ringsPerBucket = 1;
    /** Anchor the geometry was built for. */
    private int buildAnchorX = Integer.MIN_VALUE, buildAnchorZ = Integer.MIN_VALUE;
    /** Anchor the draw order was last published for. */
    private int orderAnchorX = Integer.MIN_VALUE, orderAnchorZ = Integer.MIN_VALUE;
    /** Shape the wall runs were stamped from. */
    private CloudShape shape;
    /** Staging buffer for the packed face upload. */
    private ByteBuffer uploadBuffer;

    /** Faces uploaded for the current anchor, excluding the interior faces. */
    int uploadedCount() {
        return uploadedCount;
    }

    /** Faces of the cell the camera occupies, drawn after the wall faces. */
    int interiorCount() {
        return interiorCount;
    }

    /** Anchor column the geometry was built for. */
    int buildAnchorX() {
        return buildAnchorX;
    }

    /** Anchor row the geometry was built for. */
    int buildAnchorZ() {
        return buildAnchorZ;
    }

    /** Drops the face counts without releasing the GL names, used when the geometry is rebuilt for the same anchor. */
    void clear() {
        uploadedCount = 0;
        interiorCount = 0;
    }

    /**
     * Releases the vertex array and both buffers. Safe to repeat: every step is guarded by the name still
     * being valid and the names are reset afterwards, so a second call deletes nothing.
     */
    void delete() {
        if (vao != -1) GLSMVertexInput.glDeleteVertexArrays(vao);
        if (vbo != -1) GLSMBuffers.glDeleteBuffers(vbo);
        if (ebo != -1) GLSMBuffers.glDeleteBuffers(ebo);
        vao = -1;
        vbo = -1;
        ebo = -1;
        vboCapacityBytes = 0;
        clear();
        stampedCount = 0;
        ringsPerBucket = 1;
        buildAnchorX = Integer.MIN_VALUE;
        buildAnchorZ = Integer.MIN_VALUE;
        orderAnchorX = Integer.MIN_VALUE;
        orderAnchorZ = Integer.MIN_VALUE;
        shape = null;
        uploadBuffer = null;
        Arrays.fill(wallStart, 0);
        Arrays.fill(plateStart, 0);
    }

    /** Stamps every wall run inside the disc and publishes the per wedge draw order for this anchor. */
    void build(CloudShape shape, int anchorX, int anchorZ, int radiusCells, int radiusCellsSq, int wallCutCells) {
        stampedCount = 0;
        this.shape = shape;
        ringsPerBucket = 2 * (radiusCells + MARGIN_CELLS) + 1;
        buildAnchorX = anchorX;
        buildAnchorZ = anchorZ;
        orderAnchorX = anchorX;
        orderAnchorZ = anchorZ;

        addWalls(shape, anchorX, anchorZ, radiusCells, radiusCellsSq, wallCutCells);
        finishBuild();
    }

    /** Packs one face record: the cell in the low 20 bits, then the direction and the flags. */
    private static int packFace(int cellX, int cellZ, int dir, int flags) {
        return ((cellX + 512) & 0x3FF)
            | (((cellZ + 512) & 0x3FF) << 10)
            | (dir << 20)
            | (flags << 23);
    }

    /** Appends one face record with its sort key, growing the record arrays in place. */
    private void addFace(int cellX, int cellZ, int dir, int bucket, int ring) {
        if (stampedCount + INTERIOR_FACES == faceSortKey.length) {
            faceSortKey = Arrays.copyOf(faceSortKey, faceSortKey.length * 2);
            faceData = Arrays.copyOf(faceData, faceData.length * 2);
        }
        faceData[stampedCount] = packFace(cellX, cellZ, dir, 0);
        faceSortKey[stampedCount] = bucket * ringsPerBucket + ring;
        stampedCount++;
    }

    /**
     * Appends the six faces of the camera cell when that cell is a cloud cell, so the deck has an inside
     * even though the wall runs only describe outward facing sides.
     */
    private void stampInterior(int anchorX, int anchorZ) {
        interiorCount = 0;
        if (shape == null || (shape.cellFlagsAt(anchorX, anchorZ) & CloudShape.OPAQUE) == 0) return;

        final int driftX = anchorX - buildAnchorX;
        final int driftZ = anchorZ - buildAnchorZ;
        for (int dir = DIR_DOWN; dir <= DIR_EAST; dir++) {
            faceData[uploadedCount + dir] = packFace(driftX, driftZ, dir, FLAG_INSIDE);
        }
        interiorCount = INTERIOR_FACES;
    }

    /** Stamps the four wall directions, skipping the margin ring the neighbouring anchors cover. */
    private void addWalls(CloudShape shape, int anchorX, int anchorZ, int radiusCells, int radiusCellsSq, int wallCutCells) {
        stampWallRuns(shape, shape.westRuns, DIR_WEST, true, anchorX, anchorZ, radiusCells, radiusCellsSq, wallCutCells);
        stampWallRuns(shape, shape.eastRuns, DIR_EAST, true, anchorX, anchorZ, radiusCells, radiusCellsSq, wallCutCells);
        stampWallRuns(shape, shape.northRuns, DIR_NORTH, false, anchorX, anchorZ, radiusCells, radiusCellsSq, wallCutCells);
        stampWallRuns(shape, shape.southRuns, DIR_SOUTH, false, anchorX, anchorZ, radiusCells, radiusCellsSq, wallCutCells);
    }

    /**
     * Repeats one direction's runs over the disc, applies the margin, disc and LOD cuts, and turns each
     * remaining cell into a face record bucketed by wedge and by ring distance.
     */
    private void stampWallRuns(CloudShape shape, int[] runs, int dir, boolean alongZ, int anchorX, int anchorZ,
                               int radiusCells, int radiusCellsSq, int wallCutCells) {
        final int texW = shape.width;
        final int texH = shape.height;
        final int minCell = -radiusCells;
        final int maxCell = radiusCells;
        final int nearAlwaysSq = ALWAYS_DRAWN_CELLS * ALWAYS_DRAWN_CELLS;
        final int wallCutSq = wallCutCells >= radiusCells ? Integer.MAX_VALUE : wallCutCells * wallCutCells;

        for (int r = 0; r < runs.length; r += 3) {
            final int runLength = runs[r + 2];
            final int baseX = Math.floorMod(runs[r] - anchorX, texW);
            final int baseZ = Math.floorMod(runs[r + 1] - anchorZ, texH);

            for (int kz = Math.floorDiv(minCell - baseZ - (alongZ ? runLength - 1 : 0), texH); kz <= Math.floorDiv(maxCell - baseZ, texH); kz++) {
                final int runZ = baseZ + kz * texH;
                for (int kx = Math.floorDiv(minCell - baseX - (alongZ ? 0 : runLength - 1), texW); kx <= Math.floorDiv(maxCell - baseX, texW); kx++) {
                    final int runX = baseX + kx * texW;

                    for (int step = 0; step < runLength; step++) {
                        final int cellX = alongZ ? runX : runX + step;
                        final int cellZ = alongZ ? runZ + step : runZ;

                        if (dir == DIR_WEST ? cellX < 1 - MARGIN_CELLS
                            : dir == DIR_EAST ? cellX > MARGIN_CELLS - 1
                            : dir == DIR_NORTH ? cellZ < 1 - MARGIN_CELLS
                            : cellZ > MARGIN_CELLS - 1) {
                            continue;
                        }

                        final int distSq = cellX * cellX + cellZ * cellZ;
                        if (distSq > radiusCellsSq) continue;
                        final boolean near = distSq <= nearAlwaysSq;
                        if (!near && distSq > wallCutSq) continue;

                        final int bucket = near ? BUCKET_ALWAYS_DRAWN : BUCKET_WALL_WEDGE + wedgeOf(cellX, cellZ);
                        addFace(cellX, cellZ, dir, bucket, Math.abs(cellX) + Math.abs(cellZ));
                    }
                }
            }
        }
    }

    /** Whether the published draw order was built for a different anchor than the one passed in. */
    boolean orderStale(int anchorX, int anchorZ) {
        return anchorX != orderAnchorX || anchorZ != orderAnchorZ;
    }

    /**
     * Re-buckets the already stamped faces around the current anchor and republishes the draw order. Used
     * while the camera drifts off the build anchor without a full rebuild.
     */
    void reorder(int anchorX, int anchorZ) {
        final int driftX = anchorX - buildAnchorX;
        final int driftZ = anchorZ - buildAnchorZ;
        final int nearAlwaysSq = ALWAYS_DRAWN_CELLS * ALWAYS_DRAWN_CELLS;
        for (int i = 0; i < uploadedCount; i++) {
            final int packed = faceData[i];
            final int cellX = (packed & 0x3FF) - 512 - driftX;
            final int cellZ = ((packed >> 10) & 0x3FF) - 512 - driftZ;
            final int distSq = cellX * cellX + cellZ * cellZ;
            final int bucket = distSq <= nearAlwaysSq ? BUCKET_ALWAYS_DRAWN : BUCKET_WALL_WEDGE + wedgeOf(cellX, cellZ);
            final int ring = Math.min(Math.abs(cellX) + Math.abs(cellZ), ringsPerBucket - 1);
            faceSortKey[i] = bucket * ringsPerBucket + ring;
        }
        stampInterior(anchorX, anchorZ);
        sortAndUpload();
        orderAnchorX = anchorX;
        orderAnchorZ = anchorZ;
    }

    /** Appends the interior faces and publishes the order for the build anchor. */
    private void finishBuild() {
        uploadedCount = stampedCount;
        stampInterior(buildAnchorX, buildAnchorZ);
        if (uploadedCount == 0) Arrays.fill(wallStart, 0);
        sortAndUpload();
    }

    /** Counting sort of the faces by bucket and ring, then uploads them and the per wedge range starts. */
    private void sortAndUpload() {
        final int sortKeyCount = BUCKET_COUNT * ringsPerBucket;
        if (sortKeyStart.length < sortKeyCount + 1) sortKeyStart = new int[sortKeyCount + 1];
        else Arrays.fill(sortKeyStart, 0, sortKeyCount + 1, 0);

        for (int i = 0; i < uploadedCount; i++) sortKeyStart[faceSortKey[i]]++;
        int acc = 0;
        for (int k = 0; k < sortKeyCount; k++) {
            final int keyTotal = sortKeyStart[k];
            sortKeyStart[k] = acc;
            acc += keyTotal;
        }
        sortKeyStart[sortKeyCount] = acc;

        for (int w = 0; w < WEDGE_COUNT; w++) wallStart[w] = sortKeyStart[(BUCKET_WALL_WEDGE + w) * ringsPerBucket];
        wallStart[WEDGE_COUNT] = uploadedCount;

        final int totalFaces = uploadedCount + interiorCount;
        if (totalFaces == 0) return;

        final int uploadBytes = totalFaces * FACE_BYTES;
        if (uploadBuffer == null || uploadBuffer.capacity() < uploadBytes) {
            uploadBuffer = BufferUtils.createByteBuffer(Math.max(uploadBytes, 8192));
        }
        uploadBuffer.clear();
        final IntBuffer ints = uploadBuffer.asIntBuffer();
        for (int i = 0; i < uploadedCount; i++) {
            ints.put(sortKeyStart[faceSortKey[i]]++, faceData[i]);
        }
        for (int i = 0; i < interiorCount; i++) {
            ints.put(uploadedCount + i, faceData[uploadedCount + i]);
        }
        uploadBuffer.position(0).limit(uploadBytes);

        final boolean freshBuffers = vbo == -1;
        if (freshBuffers) vbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        if (uploadBytes > vboCapacityBytes) {
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, uploadBuffer, GL15.GL_DYNAMIC_DRAW);
            vboCapacityBytes = uploadBytes;
        } else {
            GLSMBuffers.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, uploadBuffer);
        }

        if (freshBuffers) {
            ensureQuadIndices();
            if (vao == -1) vao = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(vao);
            GLSMVertexInput.glEnableVertexAttribArray(FACE_ATTRIB);
            GLSMVertexInput.glVertexAttribDivisor(FACE_ATTRIB, 1);
            GLSMVertexInput.glVertexAttribIPointer(FACE_ATTRIB, 1, GL11.GL_INT, FACE_BYTES, 0L);
            GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GLSMVertexInput.glBindVertexArray(0);
        }
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /** Creates the shared quad index buffer the instanced faces are expanded with. */
    private void ensureQuadIndices() {
        if (ebo != -1) return;
        ebo = GLSMBuffers.glGenBuffers();
        final IntBuffer indices = BufferUtils.createIntBuffer(6);
        indices.put(0).put(1).put(2).put(0).put(2).put(3);
        indices.flip();
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /** Binds the vertex array and the instance buffer for a draw. */
    void bind() {
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
    }

    /** Restores the default buffer and vertex array binding. */
    void unbind() {
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMVertexInput.glBindVertexArray(0);
    }

    /** Draws the faces in the given range, one instanced quad per face record. */
    void drawRange(int startFace, int endFace) {
        final int faces = endFace - startFace;
        if (faces <= 0) return;
        GLSMVertexInput.glVertexAttribIPointer(FACE_ATTRIB, 1, GL11.GL_INT, FACE_BYTES, (long) startFace * FACE_BYTES);
        GLSMDraws.glDrawElementsInstanced(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0L, faces);
    }

    /** Draws the six faces of the cell the camera occupies. */
    void drawInterior() {
        drawRange(uploadedCount, uploadedCount + interiorCount);
    }

    /** Plate range starts, all zero because this mesh draws only walls. */
    int[] plateStarts() {
        return plateStart;
    }

    /** Wall range starts, one entry per wedge plus the end index. */
    int[] wallStarts() {
        return wallStart;
    }
}
