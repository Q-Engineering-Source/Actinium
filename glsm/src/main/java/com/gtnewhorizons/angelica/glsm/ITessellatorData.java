package com.gtnewhorizons.angelica.glsm;

/**
 * Tessellator data needed by {@link com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer}
 * and by the batched TESR path.
 *
 * <h2>Shared vertex layout</h2>
 *
 * <p>{@link #getRawBuffer()} is an {@code int[]} of {@code getRawBufferIndex()} ints, packed
 * {@link com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil#VERTEX_SIZE eight ints per
 * vertex}. The layout is the same one the model-quad path and the batch writers use, so any
 * implementor, including the host {@code BufferBuilder} adapter, has to expose its vertices in
 * exactly this order and unit:</p>
 *
 * <table>
 *   <caption>Per-vertex int slots</caption>
 *   <tr><th>Index</th><th>Content</th><th>Unit</th></tr>
 *   <tr><td>0</td><td>position X</td><td>IEEE-754 float bit pattern</td></tr>
 *   <tr><td>1</td><td>position Y</td><td>IEEE-754 float bit pattern</td></tr>
 *   <tr><td>2</td><td>position Z</td><td>IEEE-754 float bit pattern</td></tr>
 *   <tr><td>3</td><td>texture U</td><td>IEEE-754 float bit pattern</td></tr>
 *   <tr><td>4</td><td>texture V</td><td>IEEE-754 float bit pattern</td></tr>
 *   <tr><td>5</td><td>colour</td><td>ABGR packed into an int, {@code 0xAABBGGRR}</td></tr>
 *   <tr><td>6</td><td>normal</td><td>three signed bytes, {@code x | y << 8 | z << 16}, each {@code -127..127}</td></tr>
 *   <tr><td>7</td><td>lightmap</td><td>{@code blockLight | skyLight << 16}, each {@code 0..65535}</td></tr>
 * </table>
 *
 * <p>Vertices are contiguous, so vertex {@code n} starts at int index {@code n * 8}. The indexes
 * of the four optional attributes are fixed even when the vertex does not carry them; the
 * {@code has*} queries say which slots the current format fills and which a writer has to
 * supply from GL state.</p>
 */
public interface ITessellatorData {

    boolean isDrawing();
    void setDrawing(boolean drawing);
    int getVertexCount();

    /** @return the packed vertices in the eight-int layout described above */
    int[] getRawBuffer();

    /** @return the next free int index in {@link #getRawBuffer()}, not a byte offset */
    int getRawBufferIndex();
    int getRawBufferSize();
    void setRawBufferSize(int size);
    void setRawBuffer(int[] buffer);
    int getDrawMode();
    boolean hasTexture();
    boolean hasColor();
    boolean hasNormals();
    boolean hasBrightness();
    void angelica$reset();
}
