package com.gtnewhorizon.gtnhlib.client.renderer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.*;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

import com.google.common.annotations.Beta;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IndexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VertexBufferType;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.IVertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexOptimizer;

/**
 * A {@link Tessellator} implementation that directly populates a ByteBuffer, which can then be used for VBO uploads.
 * <br>
 *
 */
public class DirectTessellator {

    private boolean isDrawing;
    private int vertexCount;
    private boolean hasColor;
    private boolean hasTexture;
    private boolean hasNormals;
    private boolean hasBrightness;
    private boolean isColorDisabled;
    private int drawMode;
    private int color;
    private int normal;
    private int brightness;
    private double textureU;
    private double textureV;
    private double xOffset;
    private double yOffset;
    private double zOffset;

    protected VertexFormat format;

    protected VertexFormat preDefinedFormat;

    protected final ByteBuffer baseBuffer; // never resized, only freed if deleteAfter is true
    protected final long baseAddress;
    protected final boolean deleteAfter;

    protected long startPtr;
    protected long writePtr;
    protected long endPtr;

    public DirectTessellator(ByteBuffer initial) {
        this(initial, false);
    }

    public DirectTessellator(int capacity) {
        this(memAlloc(capacity), true);
    }

    public DirectTessellator(int capacity, boolean deleteAfter) {
        this(memAlloc(capacity), deleteAfter);
    }

    public DirectTessellator(ByteBuffer initial, boolean deleteAfter) {
        this.baseBuffer = initial;

        this.baseAddress = memAddress0(initial);
        this.startPtr = baseAddress;
        this.writePtr = startPtr;
        this.endPtr = startPtr + initial.capacity();

        this.deleteAfter = deleteAfter;
    }

    public int draw() {
        this.isDrawing = false;
        // Note that this does not represent the actual byte size of the data,
        // but rather it returns the same as Tessellator.draw() would
        return this.vertexCount * 32;
    }

    protected long writeVertexData(VertexFormat format, int[] rawBuffer, int rawBufferIndex) {
        return format.writeToBuffer0(writePtr, rawBuffer, rawBufferIndex);
    }

    /**
     * Clears the tessellator state in preparation for new drawing.
     */
    public void reset() {
        this.vertexCount = 0;
        this.isDrawing = false;
        this.hasNormals = false;
        this.hasColor = false;
        this.hasTexture = false;
        this.hasBrightness = false;
        this.isColorDisabled = false;

        this.format = null;
        this.preDefinedFormat = null;

        if (isResized()) {
            nmemFree(startPtr);
            startPtr = baseAddress;
            endPtr = startPtr + baseBuffer.capacity();
        }

        writePtr = startPtr;
    }

    public final void startDrawing(int p_78371_1_) {
        if (this.isDrawing) {
            throw new IllegalStateException("Already tesselating!");
        }
        this.isDrawing = true;
        this.drawMode = p_78371_1_;
    }

    private void ensureCapacity(int bytes) {
        if (bufferRemaining() >= bytes) {
            return;
        }

        final long used = bufferLimit();

        int newCapacity = bufferCapacity() * 2;
        long required = used + bytes;

        while (newCapacity < required) {
            newCapacity *= 2;
        }

        if (!isResized()) {
            long newPtr = nmemAllocChecked(newCapacity);
            memCopy(startPtr, newPtr, used);
            startPtr = newPtr;
        } else {
            startPtr = nmemReallocChecked(startPtr, newCapacity);
        }

        writePtr = startPtr + used;
        endPtr = startPtr + newCapacity;
    }

    public void addVertex(double x, double y, double z) {
        if (format == null) {
            this.format = getOptimalVertexFormat();
        }

        ensureCapacity(this.format.getVertexSize());

        writePtr = format.writeToBuffer0(
                writePtr,
                this,
                (float) (x + this.xOffset),
                (float) (y + this.yOffset),
                (float) (z + this.zOffset));
        this.vertexCount++;
    }

    public final void setTextureUV(double p_78385_1_, double p_78385_3_) {
        if (!hasTexture) {
            if (preDefinedFormat != null) return;

            this.hasTexture = true;

            if (format != null) {
                fixBufferFormat();
            }
        }

        this.textureU = p_78385_1_;
        this.textureV = p_78385_3_;
    }

    public final void setNormal(float nx, float ny, float nz) {
        if (!hasNormals) {
            if (preDefinedFormat != null) return;

            this.hasNormals = true;

            if (format != null) {
                fixBufferFormat();
            }
        }

        byte b0 = (byte) ((int) (nx * 127.0F));
        byte b1 = (byte) ((int) (ny * 127.0F));
        byte b2 = (byte) ((int) (nz * 127.0F));
        this.normal = b0 & 255 | (b1 & 255) << 8 | (b2 & 255) << 16;
    }

    public final void setColorRGBA(int red, int green, int blue, int alpha) {
        if (this.isColorDisabled) return;

        if (!this.hasColor) {
            if (preDefinedFormat != null) return;

            this.hasColor = true;

            if (format != null) {
                fixBufferFormat();
            }
        }

        if (red > 255) {
            red = 255;
        } else if (red < 0) {
            red = 0;
        }

        if (green > 255) {
            green = 255;
        } else if (green < 0) {
            green = 0;
        }

        if (blue > 255) {
            blue = 255;
        } else if (blue < 0) {
            blue = 0;
        }

        if (alpha > 255) {
            alpha = 255;
        } else if (alpha < 0) {
            alpha = 0;
        }

        this.color = alpha << 24 | blue << 16 | green << 8 | red;
    }

    public final void setBrightness(int p_78380_1_) {
        if (!this.hasBrightness) {
            if (preDefinedFormat != null) return;

            this.hasBrightness = true;

            if (format != null) {
                fixBufferFormat();
            }
        }

        this.brightness = p_78380_1_;
    }

    public final IVertexArrayObject uploadToVBO(VertexBufferType bufferType) {
        if (this.drawMode == GL11.GL_QUADS) {
            return VertexOptimizer.optimizeQuads(bufferType, format, vertexCount, getWriteBuffer());
        }
        return bufferType.allocate(this.format, this.drawMode, getWriteBuffer(), vertexCount);
    }

    /**
     * Uploads the data to the vbo via {@link IVertexBuffer#update}. Requires the data to already be allocated once.
     * <p>
     * May throw {@link UnsupportedOperationException}.
     */
    public final void updateToVBO(IVertexBuffer vbo) {
        vbo.update(getWriteBuffer());
    }

    /**
     * Allocates the data to the vbo via {@link IVertexBuffer#allocate}.
     * <p>
     * Note that, depending on the {@link IVertexBuffer} type, it may not allow further allocations.
     */
    public final void allocateToVBO(IVertexBuffer vbo) {
        vbo.allocate(getWriteBuffer(), this.vertexCount);
    }

    /**
     * Uploads the data to the VBO & uploads the needed indices to the EBO.
     */
    @Beta // May change in the future
    public final void allocateToVBO(IVertexArrayObject vao, IndexBuffer ebo) {
        vao.getVBO().allocate(getWriteBuffer(), this.vertexCount / 4 * 6);
        ebo.upload(vertexCount);
    }

    public ByteBuffer getWriteBuffer() {
        ByteBuffer buffer = isResized() ? memByteBuffer(startPtr, bufferCapacity()) : this.baseBuffer;
        buffer.limit(bufferLimit());
        return buffer;
    }

    /**
     * Allocates a new ByteBuffer with the contents of the tessellator's draw. <br>
     * The buffer needs to be freed with {@link com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities#memFree}
     */
    public final ByteBuffer allocateBufferCopy() {
        final int size = bufferLimit();
        final ByteBuffer copy = memAlloc(size);
        memCopy(startPtr, memAddress0(copy), size);
        return copy;
    }

    // If some mod does something illegal (like calling setColor after a vertex has been emitted), this will result in
    // undefined behavior, but I still have to take that into account here.
    protected final void fixBufferFormat() {
        final VertexFormat oldFormat = this.format;
        final VertexFormat newFormat = this.getOptimalVertexFormat();

        final int vertexCount = this.vertexCount;
        final int newBufferSize = vertexCount * newFormat.getVertexSize();

        ByteBuffer temp = memAlloc(newBufferSize);
        long tempPtr = memAddress0(temp);

        long readPtr = startPtr;
        long writePtrTemp = tempPtr;

        for (int i = 0; i < vertexCount; i++) {
            float x = memGetFloat(readPtr);
            float y = memGetFloat(readPtr + 4);
            float z = memGetFloat(readPtr + 8);
            readPtr += 12;

            // Read other attributes from old format
            readPtr = oldFormat.readFromBuffer0(readPtr, this);

            // Write vertex into temporary buffer using new format
            writePtrTemp = newFormat.writeToBuffer0(writePtrTemp, this, x, y, z);
        }

        // Copy back to main buffer
        ensureCapacity(newBufferSize); // make sure the main buffer is large enough
        memCopy(tempPtr, startPtr, newBufferSize);
        writePtr = startPtr + newBufferSize;

        memFree(temp);
        this.format = newFormat;
    }

    private VertexFormat getOptimalVertexFormat() {
        return VertexFlags.getFormat(this.hasTexture, this.hasColor, this.hasNormals, this.hasBrightness);
    }

    public final void setVertexFormat(VertexFormat format) {
        if (this.format != null) {
            throw new IllegalStateException("Cannot call setVertexFormat() after a vertex has already been emitted!");
        }
        this.preDefinedFormat = format;
        this.format = format;
        this.hasTexture = format.hasTexture();
        this.hasNormals = format.hasNormals();
        this.hasBrightness = format.hasBrightness();
        this.hasColor = format.hasColor();
    }

    protected final int bufferCapacity() {
        return (int) (endPtr - startPtr);
    }

    protected final int bufferLimit() { // same as position
        return (int) (writePtr - startPtr);
    }

    protected final int bufferRemaining() {
        return (int) (endPtr - writePtr);
    }

    protected final boolean isResized() {
        return startPtr != baseAddress;
    }

    public final boolean isDrawing() {
        return this.isDrawing;
    }

    public final void setDrawing(boolean drawing) {
        this.isDrawing = drawing;
    }

    public final int getVertexCount() {
        return this.vertexCount;
    }

    public final boolean isEmpty() {
        return startPtr == writePtr;
    }

    public final VertexFormat getVertexFormat() {
        return format;
    }

    public final boolean hasTexture() {
        return this.hasTexture;
    }

    public final boolean hasColor() {
        return this.hasColor;
    }

    public final boolean hasNormals() {
        return this.hasNormals;
    }

    public final boolean hasBrightness() {
        return this.hasBrightness;
    }

    public final double getLastTextureU() {
        return this.textureU;
    }

    public final double getLastTextureV() {
        return this.textureV;
    }

    public final void setLastTextureUVRaw(double textureU, double textureV) {
        this.textureU = textureU;
        this.textureV = textureV;
    }

    public final int getPackedNormal() {
        return this.normal;
    }

    public final void setPackedNormalRaw(int normal) {
        this.normal = normal;
    }

    public final int getPackedColor() {
        return this.color;
    }

    public final int getPackedBrightness() {
        return this.brightness;
    }

    public final void setPackedColorRaw(int color) {
        this.color = color;
    }

    public final void setPackedBrightnessRaw(int brightness) {
        this.brightness = brightness;
    }

    public final int getDrawMode() {
        return this.drawMode;
    }

    protected void onRemovedFromStack() {
        reset();
        if (this.deleteAfter) {
            delete();
        }
    }

    /**
     * Deletes the allocated byte buffer.
     */
    public void delete() {
        reset();
        nmemFree(baseAddress);
    }

    // TessellatorManager delegates

    public static DirectTessellator startCapturing() {
        return TessellatorManager.startCapturingDirect();
    }

    public static DirectTessellator startCapturing(int capacity) {
        return TessellatorManager.startCapturingDirect(capacity);
    }

    public static void startCapturing(DirectTessellator tessellator) {
        TessellatorManager.startCapturingDirect(tessellator);
    }

    public static DirectTessellator startCapturing(VertexFormat format) {
        return TessellatorManager.startCapturingDirect(format);
    }

    @Beta // Not a stable API. May change in the future.
    public static CallbackTessellator startCapturing(DirectDrawCallback callback) {
        return TessellatorManager.startCapturingDirect(callback);
    }

    public static void stopCapturing() {
        TessellatorManager.stopCapturingDirect();
    }

    public static IVertexArrayObject stopCapturingToVBO(VertexBufferType bufferType) {
        return TessellatorManager.stopCapturingDirectToVBO(bufferType);
    }
}
