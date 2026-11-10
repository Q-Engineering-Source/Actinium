package com.gtnewhorizons.angelica.render;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

import static com.gtnewhorizons.angelica.render.CloudDisc.ALWAYS_DRAWN_CELLS;
import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGE_COUNT;
import static com.gtnewhorizons.angelica.render.CloudDisc.wedgeOf;
import static org.joml.Math.clamp;

/**
 * Cloud plates and walls as plain vertices, with the per wedge draw ranges the renderer walks.
 *
 * <p>The mesh owns one growable native buffer the CPU side emits quads into and a GL vertex array built
 * from it. Two layouts exist: the textured one carries position, texture coordinates, the vertex colour
 * slot and the packed normal with the face shade, the untextured one drops the texture coordinates. The
 * colour sits at attribute location 1 because the local Iris attribute transform reads {@code gl_Color}
 * from that location, while the shade byte lives at location 5 so the shade and the colour never share a
 * slot.
 *
 * <p>That colour slot has two consumers. The local programs read the layer colour from a uniform and the
 * shade from location 5, so the array may stay enabled and carry the packed shaded colour the tests read.
 * A pack reads the same location as {@code gl_Color}: its layout leaves the array disabled and
 * {@link #drawFace} writes the shaded layer colour as the generic attribute value before each face draw,
 * so a per frame tint change needs no vertex upload.
 *
 * <p>Quad order is published per wedge, per ring band and per face group by {@link #reorder}, which
 * rebuilds only the index buffer; {@link #build} stamps the geometry and the wall runs once per anchor.
 */
final class CloudVertexMesh {

    /** Face indices used to select the shade and the colour bytes of an emitted quad. */
    static final byte FACE_DOWN = 0, FACE_UP = 1, FACE_SIDE_X = 2, FACE_SIDE_Z = 3;
    /** Shade the face is drawn with, applied to the packed colour and to the generic pack colour. */
    static final float[] FACE_SHADE = {0.7f, 1.0f, 0.9f, 0.8f};
    /** Shade byte of each face, packed for the write layout of the current platform. */
    private static final int[] FACE_SHADE_PACKED;
    /** Attribute location of the packed normal and shade. */
    private static final int FACE_SHADE_ATTRIB = 5;
    /** Stride of the textured layout: position, UV, colour, normal and shade. */
    private static final int STRIDE_WITH_UV = 28;
    /** Offset of the texture coordinates in the textured layout. */
    private static final int UV_OFFSET_WITH_UV = 12;
    /** Offset of the packed colour in the textured layout. */
    private static final int COLOR_OFFSET_WITH_UV = 20;
    /** Offset of the packed normal and shade in the textured layout. */
    private static final int NORMAL_OFFSET_WITH_UV = 24;
    /** Stride of the untextured layout: position, colour, normal and shade. */
    private static final int STRIDE_NO_UV = 20;
    /** Offset of the packed colour in the untextured layout, which stores the normal before the colour. */
    private static final int COLOR_OFFSET_NO_UV = 16;
    /** Offset of the packed normal and shade in the untextured layout. */
    private static final int NORMAL_OFFSET_NO_UV = 12;
    /** Small inset the top surface uses so it does not z-fight with the wall tops. */
    private static final float EDGE_OVERLAP = 0.0001f;
    /** Whether the platform stores the colour and shade bytes most significant first. */
    private static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    private static final int NORMAL_POS_X = packNormal((byte) 127, (byte) 0, (byte) 0);
    private static final int NORMAL_NEG_X = packNormal((byte) -127, (byte) 0, (byte) 0);
    private static final int NORMAL_POS_Y = packNormal((byte) 0, (byte) 127, (byte) 0);
    private static final int NORMAL_NEG_Y = packNormal((byte) 0, (byte) -127, (byte) 0);
    private static final int NORMAL_POS_Z = packNormal((byte) 0, (byte) 0, (byte) 127);
    private static final int NORMAL_NEG_Z = packNormal((byte) 0, (byte) 0, (byte) -127);
    private static final int DIR_WEST = 0, DIR_EAST = 1, DIR_NORTH = 2, DIR_SOUTH = 3;
    private static final int DIR_NONE = 4;
    private static final int GROUP_PLATE_ALWAYS = 0, GROUP_PLATE_WEDGE = 1, GROUP_ALWAYS_DRAWN = 2, GROUP_WALL_WEDGE = 3;
    private static final int ORDER_GROUP_COUNT = 2 * WEDGE_COUNT + 3;
    private static final int ORDER_BANDS_PER_CELL = 4;
    private static final int WEDGE_BUCKETS = WEDGE_COUNT + 1;
    private static final int FACE_WEDGE_BUCKETS = 4 * WEDGE_BUCKETS;
    private static final int WALL_SORT_INDEX_BITS = 26;
    private static final int WALL_SORT_DISTANCE_BITS = 20;
    private static final int WALL_SORT_BUCKET_SHIFT = WALL_SORT_INDEX_BITS + WALL_SORT_DISTANCE_BITS;
    private static final long WALL_SORT_INDEX_MASK = (1L << WALL_SORT_INDEX_BITS) - 1;
    private static final int WALL_SORT_MAX_RUNS = 1 << WALL_SORT_INDEX_BITS;
    private static final int WALL_SORT_MAX_DISTANCE = (1 << WALL_SORT_DISTANCE_BITS) - 1;
    private static final int RADIX_BITS = 10;
    private static final int RADIX_SIZE = 1 << RADIX_BITS;
    private static final int RADIX_MASK = RADIX_SIZE - 1;

    static {
        FACE_SHADE_PACKED = new int[4];
        for (int i = 0; i < 4; i++) {
            final int shadeByte = Math.round(FACE_SHADE[i] * 255f) & 0xFF;
            FACE_SHADE_PACKED[i] = BIG_ENDIAN ? shadeByte : (shadeByte << 24);
        }
    }

    /** Start of each face wedge bucket in the pack shader ordering. */
    private final int[] faceWedgeStart = new int[FACE_WEDGE_BUCKETS + 1];
    /** Insertion cursor of each face wedge bucket while sorting. */
    private final int[] faceWedgeCursor = new int[FACE_WEDGE_BUCKETS];
    /** Published wall draw range starts. */
    private final int[] wallStart = new int[WEDGE_COUNT + 1];
    /** Published plate draw range starts. */
    private final int[] plateStart = new int[WEDGE_COUNT + 1];
    /** Wall starts computed by {@link #reorder} until the index upload publishes them. */
    private final int[] wallStartScratch = new int[WEDGE_COUNT + 1];
    /** Plate starts computed by {@link #reorder} until the index upload publishes them. */
    private final int[] plateStartScratch = new int[WEDGE_COUNT + 1];
    /** Quads with a computed order waiting for the index upload. */
    private int orderPendingQuads;
    /** Anchor of the pending order. */
    private int orderPendingAnchorX, orderPendingAnchorZ;
    /** Cover of the plate rectangles for the current anchor. */
    private final CloudPlateCover plates = new CloudPlateCover();
    /** Vertex array of the current layout. */
    private int vao = -1;
    /** Vertex buffer with the emitted geometry. */
    private int vbo = -1;
    /** Vertices uploaded for the current geometry. */
    private int vertexCount;
    /** Quad index buffer. */
    private int eboId = -1;
    /** Quads the index buffer currently holds. */
    private int eboQuadCapacity;
    /** Whether the index buffer holds the plain sequential order. */
    private boolean eboIsSequential;
    /** Whether the current vertex array was built for the textured layout. */
    private boolean vaoHasUv = true;
    /** Stride of the current layout. */
    private int vertexStride = STRIDE_WITH_UV;
    /** Offset of the packed normal and shade in the current layout. */
    private int normalOffset = NORMAL_OFFSET_WITH_UV;
    /** Offset of the packed colour in the current layout. */
    private int colorOffset = COLOR_OFFSET_WITH_UV;
    /** Bytes of one quad in the current layout. */
    private int quadBytes = 4 * STRIDE_WITH_UV;
    /** Native buffer the CPU side emits into. */
    private ByteBuffer emitBuffer;
    /** Native address of {@link #emitBuffer}. */
    private long emitBufferAddr;
    /** Capacity of the emit buffer in bytes. */
    private int emitBufferCapacity;
    /** Write cursor and end of the emit buffer. */
    private long writeAddr, writeEnd;
    /** Native buffer holding the pack shader ordering of the emitted quads. */
    private ByteBuffer sortedBuffer;
    /** Native address of {@link #sortedBuffer}. */
    private long sortedBufferAddr;
    /** Capacity of the sorted buffer in bytes. */
    private int sortedBufferCapacity;
    /** Face index of each emitted quad, used by the pack ordering. */
    private byte[] quadFace = new byte[2048];
    /** Quads emitted since the last build started. */
    private int quadCount;
    /** Cell of each emitted quad packed as {@code (x << 16) | z}. */
    private int[] quadCell = new int[2048];
    /** Group, wedge and direction of each emitted quad. */
    private short[] quadGroupBits = new short[2048];
    /** Cell and group recorded for the quad being emitted. */
    private int nextQuadCellX, nextQuadCellZ, nextQuadGroup, nextQuadWedge, nextQuadDir = DIR_NONE;
    /** Anchor the geometry was built for. */
    private int buildAnchorX = Integer.MIN_VALUE, buildAnchorZ = Integer.MIN_VALUE;
    /** Anchor the draw order was last published for. */
    private int orderAnchorX = Integer.MIN_VALUE, orderAnchorZ = Integer.MIN_VALUE;
    /** Radius in cells the geometry was built for, used by the reorder bands. */
    private int buildRadiusCells;
    /** Counting sort offsets of the reorder buckets. */
    private int[] orderBucketCount = new int[0];
    /** Bucket of each uploaded quad. */
    private int[] quadBucket = new int[0];
    /** Index staging buffer of the pending reorder. */
    private IntBuffer orderIndexBuffer;
    /** CPU side index staging array of the reorder. */
    private int[] orderIndexScratch = new int[0];
    /** Face bucket of each emitted quad for the pack ordering. */
    private int[] quadSortKey = new int[0];
    /** Wall runs collected for the current build, packed as {@code (dir, x, z, span)}. */
    private int[] wallRuns = new int[4096 * 4];
    /** Number of collected wall runs. */
    private int wallRunCount;
    /** Sort bucket of each wall run. */
    private int[] wallRunBucket = new int[4096];
    /** Distance of each wall run in 256ths of a cell. */
    private int[] wallRunDistance = new int[4096];
    /** Radix sort input keys of the wall runs. */
    private long[] wallSortKey = new long[4096];
    /** Radix sort destination keys of the wall runs. */
    private long[] wallSortScratch = new long[4096];
    /** Radix digit counters, reused per pass. */
    private final int[] radixCounts = new int[RADIX_SIZE];
    /** Horizontal texture scale, the reciprocal of the texture width in cells. */
    private float textureScaleX, textureScaleZ;
    /** Whether the current build targets the pack shader ordering. */
    private boolean forPackShader;
    /** Wall cut distance in cells for the current build. */
    private int wallCutCells = Integer.MAX_VALUE;
    /** Squared radius the wall runs are clipped to. */
    private int wallClipRadiusSq;
    /** Emitted vertices waiting for the upload. */
    private ByteBuffer pendingUpload;
    /** Vertex count waiting for the upload, {@code -1} when none is pending. */
    private int pendingVertices = -1;
    /** Bytes the vertex buffer can hold without reallocating. */
    private int vboCapacityBytes;
    /** Bytes of the pending upload already copied. */
    private int uploadCursorBytes = -1;
    /** Bytes of the pending upload in total. */
    private int uploadTotalBytes;
    /** Vertices of the pending upload. */
    private int uploadVertices;
    /** Base colour baked into the vertex attribute, red component. */
    private float colorR = 1.0f;
    /** Base colour baked into the vertex attribute, green component. */
    private float colorG = 1.0f;
    /** Base colour baked into the vertex attribute, blue component. */
    private float colorB = 1.0f;
    /** Base colour baked into the vertex attribute, alpha component. */
    private float colorA = 1.0f;
    /** Packed vertex colour per face, with the face shade already applied. */
    private final int[] faceColorPacked = new int[4];
    /** Index buffer reused by every mesh, grown on demand. */
    private static IntBuffer sharedSequentialIndices;
    /** Quads {@link #sharedSequentialIndices} currently holds. */
    private static int sharedSequentialQuads;
    /** Whether the current vertex array was built for a pack, whose colour comes from the generic attribute. */
    private boolean vaoForPack;

    /** Prepares the default white vertex colour so a mesh always carries a defined colour attribute. */
    CloudVertexMesh() {
        setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static int packNormal(byte nx, byte ny, byte nz) {
        final int x = nx & 0xFF, y = ny & 0xFF, z = nz & 0xFF;
        return BIG_ENDIAN ? (x << 24) | (y << 16) | (z << 8) : x | (y << 8) | (z << 16);
    }

    /**
     * Sets the layer colour before face shading, so callers must not pass a colour already multiplied by
     * that shade.
     *
     * <p>The colour reaches the local programs through {@code u_ColorMult} and never through the vertex
     * attribute. A pack reads it from attribute location 1, twice over: the plain layout bakes the shaded
     * colour into the vertex bytes, and the pack layout leaves that array disabled so {@link #drawFace} can
     * supply the shaded colour as the generic attribute value, which stays exact every frame without an
     * upload.
     *
     * @param r red component in the zero to one range
     * @param g green component in the zero to one range
     * @param b blue component in the zero to one range
     * @param a alpha of the layer
     */
    void setColor(float r, float g, float b, float a) {
        colorR = r;
        colorG = g;
        colorB = b;
        colorA = a;
        for (int face = 0; face < 4; face++) {
            final float shade = FACE_SHADE[face];
            faceColorPacked[face] = packColor(r * shade, g * shade, b * shade, a);
        }
    }

    /** Packs a colour into the byte order the vertex buffer layout expects. */
    private static int packColor(float r, float g, float b, float a) {
        final int red = toByte(r), green = toByte(g), blue = toByte(b), alpha = toByte(a);
        return BIG_ENDIAN
            ? (red << 24) | (green << 16) | (blue << 8) | alpha
            : red | (green << 8) | (blue << 16) | (alpha << 24);
    }

    /** Rounds and clamps one colour channel to the unsigned byte range. */
    private static int toByte(float channel) {
        final int raw = Math.round(channel * 255.0f);
        return raw < 0 ? 0 : Math.min(raw, 255);
    }

    /** Quads currently uploaded. */
    int quadCount() {
        return vertexCount / 4;
    }

    /** Whether the mesh has an uploaded vertex array to draw. */
    boolean built() {
        return vao != -1 && vertexCount != 0;
    }

    /** Drops the uploaded geometry and the published order without releasing the GL names. */
    void clear() {
        vertexCount = 0;
        orderAnchorX = Integer.MIN_VALUE;
        orderAnchorZ = Integer.MIN_VALUE;
    }

    /** Published plate draw range starts. */
    int[] plateStarts() {
        return plateStart;
    }

    /** Published wall draw range starts. */
    int[] wallStarts() {
        return wallStart;
    }

    /**
     * Builds the flat plate the FAST cloud mode draws: one quad covering the whole disc.
     *
     * @param shape decoded cloud texture, used for the texture scale
     * @param radiusCells radius of the disc in cells, which is also the quad extent
     * @param scrollX texture scroll along X in cells
     * @param scrollZ texture scroll along Z in cells
     * @param forPackShader whether the pack ordering is required
     */
    void buildFast(CloudShape shape, int radiusCells, float scrollX, float scrollZ, boolean forPackShader) {
        beginBuild(shape, false, forPackShader);

        nextQuadDir = DIR_NONE;
        nextQuadGroup = GROUP_PLATE_ALWAYS;
        nextQuadWedge = 0;
        nextQuadCellX = 0;
        nextQuadCellZ = 0;
        final double minCell = -radiusCells;
        final double maxCell = radiusCells;
        final float uWest = -radiusCells * textureScaleX + scrollX;
        final float uEast = radiusCells * textureScaleX + scrollX;
        final float vNorth = -radiusCells * textureScaleZ + scrollZ;
        final float vSouth = radiusCells * textureScaleZ + scrollZ;
        ensureEmitCapacity(quadBytes);
        emitQuad(minCell, 0.0, minCell, uWest, vNorth, maxCell, 0.0, minCell, uEast, vNorth,
            maxCell, 0.0, maxCell, uEast, vSouth, minCell, 0.0, maxCell, uWest, vSouth, FACE_UP, NORMAL_POS_Y);

        finishGeometry();
    }

    /**
     * Builds the layered cloud geometry for one anchor.
     *
     * @param emitUnderside whether the camera is below the deck and needs the bottom faces
     * @param emitTopSurface whether the camera is above the deck and needs the top faces
     * @param emitPlates whether the plate field is drawn at all
     * @param wallCut wall cut distance in cells, {@link Integer#MAX_VALUE} to keep every wall
     * @param plateLodCells distance in cells inside which plates are built at cell resolution
     * @param untextured whether the fully opaque white texture path is used, which drops the UV attribute
     * @param forPack whether the pack shader ordering is required
     */
    void build(CloudShape shape, int anchorX, int anchorZ, int radiusCells, int radiusCellsSq,
               double cellHeightBlocks, float scrollX, float scrollZ,
               boolean emitUnderside, boolean emitTopSurface, boolean emitPlates,
               int wallCut, int plateLodCells, boolean untextured, boolean forPack) {
        beginBuild(shape, untextured, forPack);
        this.wallCutCells = wallCut;
        buildAnchorX = anchorX;
        buildAnchorZ = anchorZ;
        buildRadiusCells = radiusCells;

        final double deckTopY = cellHeightBlocks;
        final double plateTopY = deckTopY - EDGE_OVERLAP;

        if (emitPlates) {
            addPlates(shape, anchorX, anchorZ, radiusCells, radiusCellsSq, emitUnderside, emitTopSurface, plateTopY, scrollX, scrollZ, plateLodCells);
        }

        addWalls(shape, anchorX, anchorZ, radiusCells, radiusCellsSq, deckTopY, scrollX, scrollZ);

        finishGeometry();
    }

    /** Builds only the six faces of the camera cell, used when the rest of the deck is drawn by another mesh. */
    void buildInterior(CloudShape shape, int anchorX, int anchorZ, double deckTopY,
                       float scrollX, float scrollZ, boolean untextured, boolean forPack) {
        beginBuild(shape, untextured, forPack);
        addInteriorFaces(shape, anchorX, anchorZ, deckTopY, scrollX, scrollZ);
        finishGeometry();
    }

    /**
     * Resets the emit state and selects the vertex layout for this build. The texture scale and the
     * per face colour bytes are layout independent and are refreshed here as well.
     *
     * <p>A pending upload is abandoned here because the emit buffer is about to be rewound and, when the
     * build grows, reallocated: resuming a half copied upload against it would read the wrong bytes or a
     * freed block. The vertex buffer itself is kept, so a rebuild still reuses its storage.
     */
    private void beginBuild(CloudShape shape, boolean untextured, boolean forPack) {
        discardPendingUpload();
        textureScaleX = 1.0f / shape.width;
        textureScaleZ = 1.0f / shape.height;
        forPackShader = forPack;
        if (!forPack && quadCell.length < quadFace.length) {
            quadCell = new int[quadFace.length];
            quadGroupBits = new short[quadFace.length];
        }
        final boolean withUv = !untextured;
        vertexStride = withUv ? STRIDE_WITH_UV : STRIDE_NO_UV;
        normalOffset = withUv ? NORMAL_OFFSET_WITH_UV : NORMAL_OFFSET_NO_UV;
        colorOffset = withUv ? COLOR_OFFSET_WITH_UV : COLOR_OFFSET_NO_UV;
        quadBytes = 4 * vertexStride;
        prepareEmitBuffer();
    }

    /**
     * Stamps the plate rectangles for this anchor and emits the requested faces of each rectangle. The
     * pack path emits every rectangle in cover order, the local path walks the wedge grouped order so the
     * draw ranges stay valid.
     */
    private void addPlates(CloudShape shape, int anchorX, int anchorZ, int radiusCells, int radiusCellsSq,
                           boolean emitUnderside, boolean emitTopSurface, double plateTopY, float scrollX, float scrollZ, int plateLodCells) {
        final int bytesPerRect = (emitUnderside ? quadBytes : 0) + (emitTopSurface ? quadBytes : 0);
        if (bytesPerRect == 0) return;

        plates.build(shape, anchorX, anchorZ, radiusCells, radiusCellsSq, plateLodCells, !forPackShader);

        if (forPackShader) {
            nextQuadDir = DIR_NONE;
            nextQuadGroup = GROUP_PLATE_ALWAYS;
            nextQuadWedge = 0;
            if (emitUnderside) emitPlatePass(true, false, plateTopY, scrollX, scrollZ);
            if (emitTopSurface) emitPlatePass(false, true, plateTopY, scrollX, scrollZ);
            return;
        }

        for (int i = 0; i < plates.count(); i++) {
            final int rect = plates.orderedRect(i);
            final int wedgeBucket = plates.wedgeBucket(rect);
            nextQuadDir = DIR_NONE;
            nextQuadGroup = wedgeBucket == 0 ? GROUP_PLATE_ALWAYS : GROUP_PLATE_WEDGE;
            nextQuadWedge = wedgeBucket == 0 ? 0 : wedgeBucket - 1;
            nextQuadCellX = plates.minX(rect);
            nextQuadCellZ = plates.minZ(rect);
            emitPlateRect(plates.minX(rect), plates.minZ(rect), plates.maxX(rect) + 1, plates.maxZ(rect) + 1,
                emitUnderside, emitTopSurface, plateTopY, scrollX, scrollZ, bytesPerRect);
        }
    }

    /** Emits one face pass of every plate rectangle for the pack ordering, which groups by face later. */
    private void emitPlatePass(boolean emitUnderside, boolean emitTopSurface, double plateTopY, float scrollX, float scrollZ) {
        for (int rect = 0; rect < plates.count(); rect++) {
            nextQuadCellX = plates.minX(rect);
            nextQuadCellZ = plates.minZ(rect);
            emitPlateRect(plates.minX(rect), plates.minZ(rect), plates.maxX(rect) + 1, plates.maxZ(rect) + 1,
                emitUnderside, emitTopSurface, plateTopY, scrollX, scrollZ, quadBytes);
        }
    }

    /**
     * Collects the wall runs of the four directions, sorts them by wedge band and proximity, and emits one
     * quad per run piece. The pack path skips the sorting because the pack program groups by face instead.
     */
    private void addWalls(CloudShape shape, int anchorX, int anchorZ, int radiusCells, int radiusCellsSq,
                          double deckTopY, float scrollX, float scrollZ) {
        final int texW = shape.width;
        final int texH = shape.height;

        final int bandsPerWedge = radiusCells + 1;
        final long cutCellsSq = (long) wallCutCells * wallCutCells;
        wallClipRadiusSq = forPackShader && cutCellsSq < radiusCellsSq ? (int) cutCellsSq : radiusCellsSq;
        wallRunCount = 0;
        collectWallRuns(shape.westRuns, DIR_WEST, anchorX, anchorZ, texW, texH, bandsPerWedge);
        collectWallRuns(shape.eastRuns, DIR_EAST, anchorX, anchorZ, texW, texH, bandsPerWedge);
        collectWallRuns(shape.northRuns, DIR_NORTH, anchorX, anchorZ, texW, texH, bandsPerWedge);
        collectWallRuns(shape.southRuns, DIR_SOUTH, anchorX, anchorZ, texW, texH, bandsPerWedge);
        final int found = wallRunCount;

        if (found == 0) return;

        if (forPackShader) {
            nextQuadGroup = GROUP_ALWAYS_DRAWN;
            nextQuadWedge = 0;
            for (int i = 0; i < found; i++) {
                final int run = i * 4;
                nextQuadDir = wallRuns[run];
                nextQuadCellX = wallRuns[run + 1];
                nextQuadCellZ = wallRuns[run + 2];
                emitWallRun(wallRuns[run], wallRuns[run + 1], wallRuns[run + 2], wallRuns[run + 3], deckTopY, scrollX, scrollZ);
            }
            return;
        }

        if (wallSortKey.length < found) wallSortKey = new long[found];
        for (int i = 0; i < found; i++) {
            wallSortKey[i] = ((long) wallRunBucket[i] << WALL_SORT_BUCKET_SHIFT)
                | ((long) wallRunDistance[i] << WALL_SORT_INDEX_BITS)
                | i;
        }
        radixSortWallKeys(found);

        for (int i = 0; i < found; i++) {
            final long key = wallSortKey[i];
            final int bucket = (int) (key >>> WALL_SORT_BUCKET_SHIFT);
            final int wedge = bucket == 0 ? -1 : (bucket - 1) / bandsPerWedge;
            final int run = (int) (key & WALL_SORT_INDEX_MASK) * 4;
            nextQuadGroup = bucket == 0 ? GROUP_ALWAYS_DRAWN : GROUP_WALL_WEDGE;
            nextQuadWedge = bucket == 0 ? 0 : wedge;
            nextQuadDir = wallRuns[run];
            nextQuadCellX = wallRuns[run + 1];
            nextQuadCellZ = wallRuns[run + 2];
            emitWallRun(wallRuns[run], wallRuns[run + 1], wallRuns[run + 2], wallRuns[run + 3], deckTopY, scrollX, scrollZ);
        }
    }

    /**
     * Repeats one direction's runs over the disc and splits each clipped run into pieces that stay inside
     * a single wedge and a single distance band, so the reorder can bucket whole pieces.
     */
    private void collectWallRuns(int[] runs, int dir, int anchorX, int anchorZ, int texW, int texH, int bandsPerWedge) {
        final boolean alongZ = dir == DIR_WEST || dir == DIR_EAST;
        final int clipRadiusCells = (int) Math.sqrt(wallClipRadiusSq);
        final int minCell = -clipRadiusCells;
        final int maxCell = clipRadiusCells;

        for (int r = 0; r < runs.length; r += 3) {
            final int len = runs[r + 2];
            final int baseX = Math.floorMod(runs[r] - anchorX, texW);
            final int baseZ = Math.floorMod(runs[r + 1] - anchorZ, texH);

            for (int kz = Math.floorDiv(minCell - baseZ - (alongZ ? len - 1 : 0), texH); kz <= Math.floorDiv(maxCell - baseZ, texH); kz++) {
                final int runZ = baseZ + kz * texH;
                for (int kx = Math.floorDiv(minCell - baseX - (alongZ ? 0 : len - 1), texW); kx <= Math.floorDiv(maxCell - baseX, texW); kx++) {
                    final int runX = baseX + kx * texW;

                    final int startX, startZ, span;
                    if (alongZ) {
                        if (runX * runX > wallClipRadiusSq) continue;
                        final int halfSpan = (int) Math.sqrt(wallClipRadiusSq - runX * runX);
                        final int z0 = Math.max(runZ, -halfSpan);
                        final int z1 = Math.min(runZ + len - 1, halfSpan);
                        if (z0 > z1) continue;
                        startX = runX;
                        startZ = z0;
                        span = z1 - z0 + 1;
                    } else {
                        if (runZ * runZ > wallClipRadiusSq) continue;
                        final int halfSpan = (int) Math.sqrt(wallClipRadiusSq - runZ * runZ);
                        final int x0 = Math.max(runX, -halfSpan);
                        final int x1 = Math.min(runX + len - 1, halfSpan);
                        if (x0 > x1) continue;
                        startX = x0;
                        startZ = runZ;
                        span = x1 - x0 + 1;
                    }

                    if (forPackShader) {
                        recordWallRun(dir, startX, startZ, span, 0, 0);
                        continue;
                    }

                    int pieceStart = 0;
                    int pieceWedge = wedgeOf(startX, startZ);
                    double pieceDist = Math.sqrt(startX * startX + startZ * startZ);
                    int pieceBand = (int) pieceDist;
                    for (int c = 1; c <= span; c++) {
                        final int cX = alongZ ? startX : startX + c;
                        final int cZ = alongZ ? startZ + c : startZ;
                        final boolean last = c == span;
                        final int wedgeHere = last ? -1 : wedgeOf(cX, cZ);
                        final double distHere = last ? -1.0 : Math.sqrt(cX * cX + cZ * cZ);
                        final int bandHere = last ? -1 : (int) distHere;
                        if (wedgeHere == pieceWedge && bandHere == pieceBand) {
                            if (distHere < pieceDist) pieceDist = distHere;
                            continue;
                        }

                        if (pieceBand <= wallCutCells) {
                            recordWallRun(dir,
                                alongZ ? startX : startX + pieceStart,
                                alongZ ? startZ + pieceStart : startZ,
                                c - pieceStart,
                                pieceBand <= ALWAYS_DRAWN_CELLS ? 0 : 1 + pieceWedge * bandsPerWedge + pieceBand,
                                (int) (pieceDist * 256.0));
                        }

                        pieceStart = c;
                        pieceWedge = wedgeHere;
                        pieceDist = distHere;
                        pieceBand = bandHere;
                    }
                }
            }
        }
    }

    /** Sorts the wall run keys by bucket, then distance, then insertion index, with a radix sort. */
    private void radixSortWallKeys(int count) {
        if (count < 2) return;
        if (wallSortScratch.length < count) wallSortScratch = new long[count];

        long[] src = wallSortKey;
        long[] dst = wallSortScratch;
        final int[] counts = radixCounts;

        for (int shift = WALL_SORT_INDEX_BITS; shift < Long.SIZE; shift += RADIX_BITS) {
            Arrays.fill(counts, 0);
            for (int i = 0; i < count; i++) counts[(int) ((src[i] >>> shift) & RADIX_MASK)]++;

            boolean uniform = false;
            int acc = 0;
            for (int d = 0; d < RADIX_SIZE; d++) {
                final int c = counts[d];
                if (c == count) uniform = true;
                counts[d] = acc;
                acc += c;
            }
            if (uniform) continue;

            for (int i = 0; i < count; i++) dst[counts[(int) ((src[i] >>> shift) & RADIX_MASK)]++] = src[i];
            final long[] swap = src;
            src = dst;
            dst = swap;
        }

        if (src != wallSortKey) System.arraycopy(src, 0, wallSortKey, 0, count);
    }

    /** Appends one wall run piece with its bucket and distance, growing the run arrays in place. */
    private void recordWallRun(int dir, int relX, int relZ, int span, int bucket, int distance256ths) {
        if (wallRunCount == WALL_SORT_MAX_RUNS) return;
        if (wallRunCount * 4 + 4 > wallRuns.length) {
            wallRuns = Arrays.copyOf(wallRuns, wallRuns.length * 2);
            wallRunBucket = Arrays.copyOf(wallRunBucket, wallRunBucket.length * 2);
            wallRunDistance = Arrays.copyOf(wallRunDistance, wallRunDistance.length * 2);
        }
        final int slot = wallRunCount * 4;
        wallRuns[slot] = dir;
        wallRuns[slot + 1] = relX;
        wallRuns[slot + 2] = relZ;
        wallRuns[slot + 3] = span;
        wallRunBucket[wallRunCount] = bucket;
        wallRunDistance[wallRunCount] = Math.min(distance256ths, WALL_SORT_MAX_DISTANCE);
        wallRunCount++;
    }

    /** Emits the quad of one wall run piece, choosing the winding and the normal from the direction. */
    private void emitWallRun(int dir, int relX, int relZ, int span, double deckTopY, float scrollX, float scrollZ) {
        ensureEmitCapacity(quadBytes);
        final double x0 = relX;
        final double z0 = relZ;

        if (dir == DIR_WEST || dir == DIR_EAST) {
            final double z1 = relZ + span;
            final float uCentre = (relX + 0.5F) * textureScaleX + scrollX;
            final float vNorth = relZ * textureScaleZ + scrollZ;
            final float vSouth = (relZ + span) * textureScaleZ + scrollZ;
            if (dir == DIR_WEST) {
                emitQuad(x0, 0.0, z1, uCentre, vSouth, x0, deckTopY, z1, uCentre, vSouth,
                    x0, deckTopY, z0, uCentre, vNorth, x0, 0.0, z0, uCentre, vNorth, FACE_SIDE_X, NORMAL_NEG_X);
            } else {
                final double xEast = relX + 1 - EDGE_OVERLAP;
                emitQuad(xEast, 0.0, z0, uCentre, vNorth, xEast, deckTopY, z0, uCentre, vNorth,
                    xEast, deckTopY, z1, uCentre, vSouth, xEast, 0.0, z1, uCentre, vSouth, FACE_SIDE_X, NORMAL_POS_X);
            }
        } else {
            final double x1 = relX + span;
            final float uLeft = relX * textureScaleX + scrollX;
            final float uRight = (relX + span) * textureScaleX + scrollX;
            final float vCentre = (relZ + 0.5F) * textureScaleZ + scrollZ;
            if (dir == DIR_NORTH) {
                emitQuad(x0, deckTopY, z0, uLeft, vCentre, x1, deckTopY, z0, uRight, vCentre,
                    x1, 0.0, z0, uRight, vCentre, x0, 0.0, z0, uLeft, vCentre, FACE_SIDE_Z, NORMAL_NEG_Z);
            } else {
                final double zSouth = relZ + 1 - EDGE_OVERLAP;
                emitQuad(x0, 0.0, zSouth, uLeft, vCentre, x1, 0.0, zSouth, uRight, vCentre,
                    x1, deckTopY, zSouth, uRight, vCentre, x0, deckTopY, zSouth, uLeft, vCentre, FACE_SIDE_Z, NORMAL_POS_Z);
            }
        }
    }

    /** Emits the bottom and top faces of one plate rectangle. */
    private void emitPlateRect(int relX0, int relZ0, int relX1, int relZ1, boolean emitUnderside, boolean emitTopSurface,
                               double plateTopY, float scrollX, float scrollZ, int bytesPerRect) {
        final double x0 = relX0, x1 = relX1, z0 = relZ0, z1 = relZ1;
        final float uLeft = relX0 * textureScaleX + scrollX;
        final float uRight = relX1 * textureScaleX + scrollX;
        final float vNorth = relZ0 * textureScaleZ + scrollZ;
        final float vSouth = relZ1 * textureScaleZ + scrollZ;

        ensureEmitCapacity(bytesPerRect);
        if (emitUnderside) {
            emitQuad(x0, 0.0, z0, uLeft, vNorth, x1, 0.0, z0, uRight, vNorth,
                x1, 0.0, z1, uRight, vSouth, x0, 0.0, z1, uLeft, vSouth, FACE_DOWN, NORMAL_NEG_Y);
        }
        if (emitTopSurface) {
            emitQuad(x0, plateTopY, z1, uLeft, vSouth, x1, plateTopY, z1, uRight, vSouth,
                x1, plateTopY, z0, uRight, vNorth, x0, plateTopY, z0, uLeft, vNorth, FACE_UP, NORMAL_POS_Y);
        }
    }

    /** Emits the six faces of the camera cell when that cell is a cloud cell. */
    private void addInteriorFaces(CloudShape shape, int anchorX, int anchorZ, double deckTopY, float scrollX, float scrollZ) {
        if ((shape.cellFlagsAt(anchorX, anchorZ) & CloudShape.OPAQUE) == 0) return;

        nextQuadDir = DIR_NONE;
        nextQuadGroup = GROUP_ALWAYS_DRAWN;
        nextQuadWedge = 0;
        nextQuadCellX = 0;
        nextQuadCellZ = 0;

        final float uLeft = scrollX;
        final float uRight = textureScaleX + scrollX;
        final float uCentre = 0.5F * textureScaleX + scrollX;
        final float vNorth = scrollZ;
        final float vSouth = textureScaleZ + scrollZ;
        final float vCentre = 0.5F * textureScaleZ + scrollZ;

        final double x0 = 0.0;
        final double x1 = 1.0;
        final double z0 = 0.0;
        final double z1 = 1.0;
        final double yBottom = 0.0;
        final double yTop = deckTopY;

        ensureEmitCapacity(6 * quadBytes);
        emitQuad(x0, yBottom, z1, uLeft, vSouth, x1, yBottom, z1, uRight, vSouth, x1, yBottom, z0, uRight, vNorth, x0, yBottom, z0, uLeft, vNorth, FACE_DOWN, NORMAL_POS_Y);
        emitQuad(x0, yTop, z0, uLeft, vNorth, x1, yTop, z0, uRight, vNorth, x1, yTop, z1, uRight, vSouth, x0, yTop, z1, uLeft, vSouth, FACE_UP, NORMAL_NEG_Y);
        emitQuad(x0, yBottom, z0, uCentre, vNorth, x0, yTop, z0, uCentre, vNorth, x0, yTop, z1, uCentre, vSouth, x0, yBottom, z1, uCentre, vSouth, FACE_SIDE_X, NORMAL_POS_X);
        emitQuad(x1, yBottom, z1, uCentre, vSouth, x1, yTop, z1, uCentre, vSouth, x1, yTop, z0, uCentre, vNorth, x1, yBottom, z0, uCentre, vNorth, FACE_SIDE_X, NORMAL_NEG_X);
        emitQuad(x0, yBottom, z0, uLeft, vCentre, x1, yBottom, z0, uRight, vCentre, x1, yTop, z0, uRight, vCentre, x0, yTop, z0, uLeft, vCentre, FACE_SIDE_Z, NORMAL_POS_Z);
        emitQuad(x0, yTop, z1, uLeft, vCentre, x1, yTop, z1, uRight, vCentre, x1, yBottom, z1, uRight, vCentre, x0, yBottom, z1, uLeft, vCentre, FACE_SIDE_Z, NORMAL_NEG_Z);
    }

    /** Publishes the emitted vertices as the pending upload, sorted first for the pack ordering. */
    private void finishGeometry() {
        final int totalBytes = (int) (writeAddr - emitBufferAddr);
        final int vertices = totalBytes / vertexStride;
        pendingVertices = vertices;
        if (vertices == 0) {
            pendingUpload = null;
            return;
        }
        if (forPackShader) {
            pendingUpload = sortQuadsByFace(totalBytes);
        } else {
            emitBuffer.position(0).limit(totalBytes);
            pendingUpload = emitBuffer;
        }
    }

    /** Drops a pending upload without uploading it, used when the build is superseded. */
    void discardPendingUpload() {
        pendingVertices = -1;
        pendingUpload = null;
        uploadCursorBytes = -1;
    }

    /** Uploads the pending vertices in one slice. */
    void uploadBuilt() {
        if (beginUpload()) uploadSlice(Integer.MAX_VALUE);
    }

    /**
     * Starts the upload of the pending vertices, allocating or reallocating the vertex buffer when the
     * current one cannot hold the geometry. Returns whether a slice upload is required.
     */
    boolean beginUpload() {
        final int vertices = pendingVertices;
        pendingVertices = -1;
        if (vertices <= 0) {
            pendingUpload = null;
            deleteVao();
            return false;
        }

        final boolean wantUv = vertexStride == STRIDE_WITH_UV;
        if (vao != -1 && (vaoHasUv != wantUv || vaoForPack != forPackShader)) {
            deleteVao();
        }

        uploadVertices = vertices;
        uploadTotalBytes = vertices * vertexStride;

        if (vbo != -1 && uploadTotalBytes <= vboCapacityBytes) {
            uploadCursorBytes = 0;
            return true;
        }

        final ByteBuffer source = pendingUpload;
        final int roomyVertices = clamp(vertices, vertices + (vertices >> 1), source.capacity() / vertexStride);
        source.position(0).limit(roomyVertices * vertexStride);
        if (vbo == -1) vbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, source, GL15.GL_DYNAMIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        vboCapacityBytes = roomyVertices * vertexStride;
        vaoHasUv = wantUv;
        vaoForPack = forPackShader;
        setupVertexLayout(wantUv, forPackShader);
        uploadCursorBytes = uploadTotalBytes;
        return true;
    }

    /**
     * Uploads at most the given number of bytes of the pending geometry, so a large build can be spread
     * over several frames. Returns whether the upload is complete.
     */
    boolean uploadSlice(int maxBytes) {
        if (uploadCursorBytes < 0) return true;
        if (uploadCursorBytes < uploadTotalBytes) {
            final int sliceBytes = Math.min(maxBytes, uploadTotalBytes - uploadCursorBytes);
            final ByteBuffer source = pendingUpload;
            source.position(uploadCursorBytes).limit(uploadCursorBytes + sliceBytes);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLSMBuffers.glBufferSubData(GL15.GL_ARRAY_BUFFER, uploadCursorBytes, source);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            uploadCursorBytes += sliceBytes;
            if (uploadCursorBytes < uploadTotalBytes) return false;
        }
        finishUpload();
        return true;
    }

    /** Completes the upload, builds the sequential index buffer and invalidates the published order. */
    private void finishUpload() {
        uploadCursorBytes = -1;
        pendingUpload = null;
        vertexCount = uploadVertices;
        ensureEbo(uploadVertices / 4);
        orderAnchorX = Integer.MIN_VALUE;
        orderAnchorZ = Integer.MIN_VALUE;
    }

    /**
     * Creates the vertex array for the current layout and points every attribute at the vertex buffer.
     *
     * <p>Location 0 is the position, 1 the colour, 2 the texture coordinates of the textured layout, 4 the
     * signed normal and 5 the unsigned normal and shade byte the cloud shaders take the face shade from. The
     * normal and the shade share one four byte field: the normal reads its first three signed bytes, the
     * shade only reads the fourth.
     *
     * <p>Location 1 is a real byte attribute for the local programs, which read the layer colour from
     * {@code u_ColorMult} and never touch it. A shader pack reads the same location as {@code gl_Color}, so
     * the pack layout leaves the array disabled and {@link #drawFace} supplies the current face colour as the
     * generic attribute value. That keeps the pack colour exact every frame without touching the vertex
     * buffer, which a build baked colour could not do.
     */
    private void setupVertexLayout(boolean withUv, boolean forPack) {
        if (vao == -1) vao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        GLSMVertexInput.glEnableVertexAttribArray(0);
        GLSMVertexInput.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, vertexStride, 0L);
        GLSMVertexInput.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, vertexStride, colorOffset);
        if (forPack) GLSMVertexInput.glDisableVertexAttribArray(1);
        else GLSMVertexInput.glEnableVertexAttribArray(1);
        if (withUv) {
            GLSMVertexInput.glEnableVertexAttribArray(2);
            GLSMVertexInput.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, vertexStride, UV_OFFSET_WITH_UV);
        } else {
            GLSMVertexInput.glDisableVertexAttribArray(2);
        }
        GLSMVertexInput.glEnableVertexAttribArray(4);
        GLSMVertexInput.glVertexAttribPointer(4, 3, GL11.GL_BYTE, true, vertexStride, normalOffset);
        GLSMVertexInput.glEnableVertexAttribArray(FACE_SHADE_ATTRIB);
        GLSMVertexInput.glVertexAttribPointer(FACE_SHADE_ATTRIB, 4, GL11.GL_UNSIGNED_BYTE, true, vertexStride, normalOffset);

        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMVertexInput.glBindVertexArray(0);
    }

    /** Whether the published draw order was built for a different anchor. */
    boolean orderStale(int anchorX, int anchorZ) {
        return anchorX != orderAnchorX || anchorZ != orderAnchorZ;
    }

    /** Whether a draw order was ever published. */
    boolean orderPublished() {
        return orderAnchorX != Integer.MIN_VALUE;
    }

    /**
     * Recomputes the quad order for the current anchor without touching the vertex data: the index buffer
     * is rebuilt so the draw ranges start at the wedges nearest the camera.
     */
    void reorder(int anchorX, int anchorZ) {
        final int quads = vertexCount / 4;
        if (quads == 0) return;
        if (quads > quadCell.length) return;

        final int dx = anchorX - buildAnchorX;
        final int dz = anchorZ - buildAnchorZ;
        final int bands = (buildRadiusCells + 2) * ORDER_BANDS_PER_CELL;
        final int bucketCount = ORDER_GROUP_COUNT * bands;

        if (orderBucketCount.length < bucketCount + 1) orderBucketCount = new int[bucketCount + 1];
        else Arrays.fill(orderBucketCount, 0, bucketCount + 1, 0);

        if (quadBucket.length < quads) quadBucket = new int[quads];

        for (int q = 0; q < quads; q++) {
            final int cell = quadCell[q];
            final int cx = (short) (cell >> 16) - dx;
            final int cz = (short) cell - dz;
            final int groupBits = quadGroupBits[q];
            final int group = (groupBits >> 5) & 3;
            final int wedge = groupBits & 31;

            final boolean facingAway = group == GROUP_WALL_WEDGE && switch ((groupBits >> 7) & 7) {
                case DIR_WEST -> cx < 1;
                case DIR_EAST -> cx > -1;
                case DIR_NORTH -> cz < 1;
                case DIR_SOUTH -> cz > -1;
                default -> false;
            };

            int band = (int) (Math.sqrt((double) cx * cx + (double) cz * cz) * ORDER_BANDS_PER_CELL);
            if (band >= bands) band = bands - 1;

            final int groupSlot = facingAway ? 2 + 2 * WEDGE_COUNT : switch (group) {
                case GROUP_PLATE_ALWAYS -> 0;
                case GROUP_PLATE_WEDGE -> 1 + wedge;
                case GROUP_ALWAYS_DRAWN -> 1 + WEDGE_COUNT;
                default -> 2 + WEDGE_COUNT + wedge;
            };
            final int bucket = groupSlot * bands + band;
            quadBucket[q] = bucket;
            orderBucketCount[bucket]++;
        }

        int acc = 0;
        for (int b = 0; b < bucketCount; b++) {
            final int count = orderBucketCount[b];
            orderBucketCount[b] = acc;
            acc += count;
        }
        orderBucketCount[bucketCount] = acc;

        for (int w = 0; w < WEDGE_COUNT; w++) plateStartScratch[w] = orderBucketCount[(1 + w) * bands];
        plateStartScratch[WEDGE_COUNT] = orderBucketCount[(1 + WEDGE_COUNT) * bands];
        for (int w = 0; w < WEDGE_COUNT; w++) wallStartScratch[w] = orderBucketCount[(2 + WEDGE_COUNT + w) * bands];
        wallStartScratch[WEDGE_COUNT] = orderBucketCount[(2 + 2 * WEDGE_COUNT) * bands];

        final int needed = quads * 6;
        if (orderIndexBuffer == null || orderIndexBuffer.capacity() < needed) {
            orderIndexBuffer = BufferUtils.createIntBuffer(Math.max(needed, 4096));
        }

        if (orderIndexScratch.length < needed) orderIndexScratch = new int[needed];
        final int[] out = orderIndexScratch;
        for (int q = 0; q < quads; q++) {
            final int slot = orderBucketCount[quadBucket[q]]++;
            final int v = q * 4;
            final int base = slot * 6;
            out[base] = v;
            out[base + 1] = v + 1;
            out[base + 2] = v + 2;
            out[base + 3] = v;
            out[base + 4] = v + 2;
            out[base + 5] = v + 3;
        }

        final IntBuffer indices = orderIndexBuffer;
        indices.clear();
        indices.put(out, 0, needed);
        indices.flip();

        orderPendingQuads = quads;
        orderPendingAnchorX = anchorX;
        orderPendingAnchorZ = anchorZ;
    }

    /** Uploads the pending index order and publishes the draw range starts that go with it. */
    void reorderUpload() {
        if (orderPendingQuads <= 0) return;
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, orderIndexBuffer, GL15.GL_DYNAMIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        System.arraycopy(plateStartScratch, 0, plateStart, 0, plateStart.length);
        System.arraycopy(wallStartScratch, 0, wallStart, 0, wallStart.length);
        eboQuadCapacity = orderPendingQuads;
        eboIsSequential = false;
        orderAnchorX = orderPendingAnchorX;
        orderAnchorZ = orderPendingAnchorZ;
        orderPendingQuads = 0;
    }

    /** Drops a pending reorder without uploading it. */
    void discardPendingReorder() {
        orderPendingQuads = 0;
    }

    /** Creates or grows the index buffer of one quad's indices per emitted quad. */
    private void ensureEbo(int quads) {
        if (eboId == -1) {
            eboId = GLSMBuffers.glGenBuffers();
        }

        if (quads <= eboQuadCapacity && eboIsSequential) return;
        eboIsSequential = true;
        final int capacity = Math.max(quads + (quads >> 1), 4096);
        final IntBuffer indices = sequentialIndices(capacity);
        indices.position(0).limit(capacity * 6);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        eboQuadCapacity = capacity;
    }

    /** Shared sequential index pattern, grown to the largest mesh seen so far. */
    private static IntBuffer sequentialIndices(int quads) {
        if (sharedSequentialIndices != null && sharedSequentialQuads >= quads) return sharedSequentialIndices;

        final IntBuffer indices = BufferUtils.createIntBuffer(quads * 6);
        for (int q = 0; q < quads; q++) {
            final int v = q * 4;
            indices.put(v).put(v + 1).put(v + 2);
            indices.put(v).put(v + 2).put(v + 3);
        }
        indices.flip();
        sharedSequentialIndices = indices;
        sharedSequentialQuads = quads;
        return indices;
    }

    /** Releases the index buffer name. Safe to repeat. */
    void deleteEbo() {
        if (eboId != -1) {
            GLSMBuffers.glDeleteBuffers(eboId);
            eboId = -1;
            eboQuadCapacity = 0;
        }
    }

    /** Releases the vertex array and drops the uploaded geometry. Safe to repeat. */
    void deleteVao() {
        if (vao != -1) {
            GLSMVertexInput.glDeleteVertexArrays(vao);
            vao = -1;
        }
        if (vbo != -1) {
            GLSMBuffers.glDeleteBuffers(vbo);
            vbo = -1;
        }
        vboCapacityBytes = 0;
        vertexCount = 0;
    }

    /**
     * Releases everything the mesh owns: the vertex array, the index buffer, the pending upload or
     * reorder state and both native buffers. Called on world unload, so a leaked native buffer would
     * outlive the world. Safe to repeat.
     */
    void delete() {
        deleteVao();
        deleteEbo();
        discardPendingUpload();
        discardPendingReorder();
        if (emitBuffer != null) {
            MemoryUtilities.memFree(emitBuffer);
            emitBuffer = null;
            emitBufferAddr = 0L;
            emitBufferCapacity = 0;
            writeAddr = 0L;
            writeEnd = 0L;
        }
        if (sortedBuffer != null) {
            MemoryUtilities.memFree(sortedBuffer);
            sortedBuffer = null;
            sortedBufferAddr = 0L;
            sortedBufferCapacity = 0;
        }
        orderIndexBuffer = null;
        orderIndexScratch = new int[0];
        quadSortKey = new int[0];
        quadBucket = new int[0];
        orderBucketCount = new int[0];
        buildAnchorX = Integer.MIN_VALUE;
        buildAnchorZ = Integer.MIN_VALUE;
        buildRadiusCells = 0;
        quadCount = 0;
    }

    /** Binds the vertex array and the index buffer for a draw. */
    void bind() {
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
    }

    /** Restores the default vertex array binding. */
    void unbind() {
        GLSMVertexInput.glBindVertexArray(0);
    }

    /** Draws the quads in the given range of the published order. */
    void drawRange(int startQuad, int endQuad) {
        final int quads = endQuad - startQuad;
        if (quads <= 0) return;
        GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, quads * 6, GL11.GL_UNSIGNED_INT, (long) startQuad * 6L * 4L);
    }

    /** Draws every uploaded quad in the order the index buffer holds. */
    void drawAll() {
        GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, (vertexCount / 4) * 6, GL11.GL_UNSIGNED_INT, 0L);
    }

    /** Whether the given face has no quads in the pack ordering. */
    boolean faceEmpty(int face) {
        final int base = face * WEDGE_BUCKETS;
        return faceWedgeStart[base + WEDGE_BUCKETS] == faceWedgeStart[base];
    }

    /**
     * Draws every quad of one face in the pack ordering, with the layer colour of that face as the generic
     * value of attribute location 1. The pack layout leaves that array disabled, so the value set here is
     * what the pack program reads as {@code gl_Color}; a layout that enables the array ignores it.
     *
     * <p>The colour goes through the tracked current colour on purpose: every GLSM draw re-uploads that
     * value to location 1 before drawing, so a direct generic attribute write would be overwritten. This is
     * also the call the fixed function pipeline used, which keeps the pack colour exact per face and per
     * frame without touching the vertex buffer.
     */
    void drawFace(int face) {
        final int base = face * WEDGE_BUCKETS;
        final int start = faceWedgeStart[base];
        final int end = faceWedgeStart[base + WEDGE_BUCKETS];
        if (end <= start) return;
        final float shade = FACE_SHADE[face];
        GLSMCurrentAttribs.glColor4f(colorR * shade, colorG * shade, colorB * shade, colorA);
        drawRange(start, end);
    }

    /** Allocates the native emit buffer on first use and rewinds it for the current build. */
    private void prepareEmitBuffer() {
        if (emitBuffer == null) {
            emitBufferCapacity = 256 * 1024;
            emitBuffer = MemoryUtilities.memRealloc((ByteBuffer) null, emitBufferCapacity);
            emitBufferAddr = MemoryUtilities.memAddress0(emitBuffer);
        }

        emitBuffer.clear();
        writeAddr = emitBufferAddr;
        writeEnd = emitBufferAddr + emitBufferCapacity;
        quadCount = 0;
    }

    /** Doubles the native emit buffer until it can hold the requested total size, keeping the written bytes. */
    private void growEmitBuffer(int neededBytes) {
        final long oldBytes = writeAddr - emitBufferAddr;
        int newCap = emitBufferCapacity;
        while (newCap < neededBytes) newCap *= 2;
        emitBuffer = MemoryUtilities.memRealloc(emitBuffer, newCap);
        emitBufferAddr = MemoryUtilities.memAddress0(emitBuffer);
        emitBufferCapacity = newCap;
        writeAddr = emitBufferAddr + oldBytes;
        writeEnd = emitBufferAddr + newCap;
    }

    /** Grows the emit buffer when the next quad does not fit. */
    private void ensureEmitCapacity(int bytes) {
        if (writeAddr + bytes > writeEnd) {
            growEmitBuffer((int) (writeAddr - emitBufferAddr) + bytes);
        }
    }

    /** Writes one quad, recording its face and the cell and group the reorder buckets it by. */
    private void emitQuad(double x0, double y0, double z0, float u0, float v0, double x1, double y1, double z1, float u1, float v1, double x2, double y2, double z2, float u2, float v2, double x3, double y3, double z3, float u3, float v3, byte face, int packedNormal) {
        if (quadCount == quadFace.length) {
            quadFace = Arrays.copyOf(quadFace, quadFace.length * 2);
            if (!forPackShader) {
                quadCell = Arrays.copyOf(quadCell, quadCell.length * 2);
                quadGroupBits = Arrays.copyOf(quadGroupBits, quadGroupBits.length * 2);
            }
        }
        if (!forPackShader) {
            quadCell[quadCount] = (nextQuadCellX << 16) | (nextQuadCellZ & 0xFFFF);
            quadGroupBits[quadCount] = (short) ((nextQuadDir << 7) | (nextQuadGroup << 5) | nextQuadWedge);
        }
        quadFace[quadCount++] = face;

        final int normalAndShade = packedNormal | FACE_SHADE_PACKED[face];
        final int packedColor = faceColorPacked[face];
        long addr = writeAddr;
        addr = writeVertex(addr, x0, y0, z0, u0, v0, normalAndShade, packedColor);
        addr = writeVertex(addr, x1, y1, z1, u1, v1, normalAndShade, packedColor);
        addr = writeVertex(addr, x2, y2, z2, u2, v2, normalAndShade, packedColor);
        addr = writeVertex(addr, x3, y3, z3, u3, v3, normalAndShade, packedColor);
        writeAddr = addr;
    }

    /** Writes one vertex in the current layout and returns the address of the next vertex. */
    private long writeVertex(long addr, double x, double y, double z, float u, float v, int normalAndShade, int packedColor) {
        MemoryUtilities.memPutFloat(addr, (float) x);
        MemoryUtilities.memPutFloat(addr + 4, (float) y);
        MemoryUtilities.memPutFloat(addr + 8, (float) z);
        if (normalOffset == NORMAL_OFFSET_WITH_UV) {
            MemoryUtilities.memPutFloat(addr + UV_OFFSET_WITH_UV, u);
            MemoryUtilities.memPutFloat(addr + UV_OFFSET_WITH_UV + 4, v);
        }
        MemoryUtilities.memPutInt(addr + colorOffset, packedColor);
        MemoryUtilities.memPutInt(addr + normalOffset, normalAndShade);
        return addr + vertexStride;
    }

    /**
     * Reorders the emitted quads by face for the pack path, which draws one face at a time. Returns the
     * buffer holding the ordered vertices, which is either the emit buffer or the sorted buffer.
     */
    private ByteBuffer sortQuadsByFace(int emittedBytes) {
        final int quadTotal = quadCount;
        Arrays.fill(faceWedgeStart, 0);

        if (quadSortKey.length < quadTotal) quadSortKey = new int[quadTotal];
        final byte[] faces = quadFace;
        boolean alreadyGrouped = true;
        int previousKey = 0;
        for (int q = 0; q < quadTotal; q++) {
            final int key = faces[q] * WEDGE_BUCKETS;
            if (key < previousKey) alreadyGrouped = false;
            previousKey = key;
            quadSortKey[q] = key;
            faceWedgeStart[key]++;
        }

        int acc = 0;
        for (int b = 0; b < FACE_WEDGE_BUCKETS; b++) {
            final int count = faceWedgeStart[b];
            faceWedgeStart[b] = acc;
            faceWedgeCursor[b] = acc;
            acc += count;
        }
        faceWedgeStart[FACE_WEDGE_BUCKETS] = acc;

        if (alreadyGrouped) {
            emitBuffer.position(0).limit(emittedBytes);
            return emitBuffer;
        }

        final int totalBytes = quadTotal * quadBytes;
        if (sortedBuffer == null || sortedBufferCapacity < totalBytes) {
            int rounded = Math.max(totalBytes, 64 * 1024);
            int pow2 = Integer.highestOneBit(rounded - 1) << 1;
            if (pow2 <= 0) pow2 = rounded;
            sortedBuffer = MemoryUtilities.memRealloc(sortedBuffer, pow2);
            sortedBufferAddr = MemoryUtilities.memAddress0(sortedBuffer);
            sortedBufferCapacity = pow2;
        }

        final long srcBase = emitBufferAddr;
        int q = 0;
        while (q < quadTotal) {
            final int key = quadSortKey[q];
            int runEnd = q + 1;
            while (runEnd < quadTotal && quadSortKey[runEnd] == key) runEnd++;
            final int runLen = runEnd - q;
            final long runBytes = (long) runLen * quadBytes;
            MemoryUtilities.memCopy(srcBase + (long) q * quadBytes,
                sortedBufferAddr + (long) faceWedgeCursor[key] * quadBytes, runBytes);
            faceWedgeCursor[key] += runLen;
            q = runEnd;
        }

        sortedBuffer.position(0).limit(totalBytes);
        return sortedBuffer;
    }
}
