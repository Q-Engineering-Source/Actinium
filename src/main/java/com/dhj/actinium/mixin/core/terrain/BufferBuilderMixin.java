package com.dhj.actinium.mixin.core.terrain;

import com.dhj.actinium.render.ProjectiveTexCoordBuffer;
import com.dhj.actinium.render.ProjectiveTexCoordWriter;
import com.dhj.actinium.render.vertex.DirectBufferAddress;
import com.dhj.actinium.render.vertex.FastVertexLayout;
import com.dhj.actinium.render.vertex.VertexWriters;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.rendering.tesr.SharedVertexLayout;
import net.coderbot.iris.celeritas.buffer.ShaderMaterialOverrideState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;
import dhj.embeddedt.embeddium.api.shader.buffer.BufferBuilderExtension;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements BufferBuilderExtension, ProjectiveTexCoordBuffer, ITessellatorData {
    @Shadow
    private ByteBuffer byteBuffer;

    @Shadow
    private int vertexCount;

    @Shadow
    private VertexFormat vertexFormat;

    @Shadow
    private int vertexFormatIndex;

    @Shadow
    private VertexFormatElement vertexFormatElement;

    @Shadow
    private int drawMode;

    @Shadow
    private boolean isDrawing;

    @Shadow
    private boolean noColor;

    @Shadow
    private double xOffset;

    @Shadow
    private double yOffset;

    @Shadow
    private double zOffset;

    @Shadow
    public abstract void reset();

    @Invoker("nextVertexFormatIndex")
    protected abstract void actinium$nextVertexFormatIndex();

    @Unique
    private long actinium$bufferAddress;

    @Unique
    private List<VanillaQuadContext> actinium$quadContexts;

    @Unique
    private @Nullable VanillaQuadContext actinium$activeQuadContext;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void actinium$init(int bufferSizeIn, CallbackInfo ci) {
        this.actinium$quadContexts = new ArrayList<>();
        this.actinium$refreshBufferAddress();
    }

    @Inject(method = "growBuffer", at = @At("TAIL"))
    private void actinium$growBuffer(int increaseAmount, CallbackInfo ci) {
        this.actinium$refreshBufferAddress();
    }

    @Unique
    private void actinium$refreshBufferAddress() {
        this.actinium$bufferAddress = DirectBufferAddress.of(this.byteBuffer);
    }

    /**
     * One range check per attribute write reproduces the original per-store bounds
     * semantics (contiguous span ending at offset + componentCount * type size). An
     * out-of-range cursor means the active format changed without a realigning reset.
     */
    @Unique
    private int actinium$writeOffset(int componentCount) {
        int[] offsets = ((FastVertexLayout) (Object) this.vertexFormat).actinium$offsets();
        int index = this.vertexFormatIndex;
        if (index < 0 || index >= offsets.length) {
            throw new IllegalStateException(
                "BufferBuilder element cursor " + index + " is out of sync with format " + this.vertexFormat);
        }
        int offset = this.vertexCount * this.vertexFormat.getSize() + offsets[index];
        int end = offset + componentCount * this.vertexFormatElement.getType().getSize();
        int limit = this.byteBuffer.limit();
        if (end > limit) {
            throw new IndexOutOfBoundsException(
                "Vertex write [" + offset + ", " + end + ") exceeds buffer limit " + limit);
        }
        return offset;
    }

    @Inject(method = "begin", at = @At("HEAD"))
    private void actinium$begin(int glMode, VertexFormat format, CallbackInfo ci) {
        this.actinium$quadContexts.clear();
        this.actinium$activeQuadContext = null;
    }

    @Inject(method = "addVertexData", at = @At("TAIL"))
    private void actinium$addVertexData(int[] vertexData, CallbackInfo ci) {
        if (this.actinium$activeQuadContext != null) {
            this.actinium$quadContexts.add(this.actinium$snapshotQuadContext());
        }
    }

    @Inject(method = "endVertex", at = @At("TAIL"))
    private void actinium$endVertex(CallbackInfo ci) {
        if (this.actinium$activeQuadContext != null && this.drawMode == GL11.GL_QUADS && (this.vertexCount & 3) == 0) {
            this.actinium$quadContexts.add(this.actinium$snapshotQuadContext());
        }
    }

    @Unique
    private VanillaQuadContext actinium$snapshotQuadContext() {
        int shaderOverrideBlockId = ShaderMaterialOverrideState.getBlockId();
        return shaderOverrideBlockId >= 0
                ? this.actinium$activeQuadContext.withBlockStateId(shaderOverrideBlockId)
                : this.actinium$activeQuadContext;
    }

    /** Reproduces the original offset arithmetic and translation handling. */
    @Overwrite
    public BufferBuilder pos(double x, double y, double z) {
        VertexWriters.forType(this.vertexFormatElement.getType()).writePosition(
            this.actinium$bufferAddress + this.actinium$writeOffset(3),
            x, y, z, this.xOffset, this.yOffset, this.zOffset);
        this.actinium$nextVertexFormatIndex();
        return (BufferBuilder) (Object) this;
    }

    /**
     * Returns without writing or advancing the cursor when noColor is set, like the
     * original; the byte-wise layout (platform-dependent byte order) lives in the writer.
     */
    @Overwrite
    public BufferBuilder color(int red, int green, int blue, int alpha) {
        if (this.noColor) {
            return (BufferBuilder) (Object) this;
        }
        VertexWriters.forType(this.vertexFormatElement.getType()).writeColor(
            this.actinium$bufferAddress + this.actinium$writeOffset(4), red, green, blue, alpha);
        this.actinium$nextVertexFormatIndex();
        return (BufferBuilder) (Object) this;
    }

    /**
     * Writes the texture coordinate element through the pre-selected raw writer,
     * preserving the original per-type component order.
     */
    @Overwrite
    public BufferBuilder tex(double u, double v) {
        VertexWriters.forType(this.vertexFormatElement.getType()).writeTexCoord(
            this.actinium$bufferAddress + this.actinium$writeOffset(2), u, v);
        this.actinium$nextVertexFormatIndex();
        return (BufferBuilder) (Object) this;
    }

    /**
     * Writes the normal element through the pre-selected raw writer. No translation is
     * applied, matching the original method.
     */
    @Overwrite
    public BufferBuilder normal(float x, float y, float z) {
        VertexWriters.forType(this.vertexFormatElement.getType()).writeNormal(
            this.actinium$bufferAddress + this.actinium$writeOffset(3), x, y, z);
        this.actinium$nextVertexFormatIndex();
        return (BufferBuilder) (Object) this;
    }

    /**
     * Writes the lightmap element through the pre-selected raw writer, preserving the
     * original per-type component order: the block light component comes first for the
     * integer-width types.
     */
    @Overwrite
    public BufferBuilder lightmap(int skyLight, int blockLight) {
        VertexWriters.forType(this.vertexFormatElement.getType()).writeLightmap(
            this.actinium$bufferAddress + this.actinium$writeOffset(2), skyLight, blockLight);
        this.actinium$nextVertexFormatIndex();
        return (BufferBuilder) (Object) this;
    }

    /**
     * The ring encodes the original advance (one step forward with wraparound past
     * PADDING). The original silently kept drawing through a wrong element on cursor
     * desync; the mismatch is reported instead.
     */
    @Overwrite
    public void nextVertexFormatIndex() {
        int[] nextIndices = ((FastVertexLayout) (Object) this.vertexFormat).actinium$nextIndices();
        int index = this.vertexFormatIndex;
        if (index < 0 || index >= nextIndices.length) {
            throw new IllegalStateException(
                "BufferBuilder element cursor " + index + " is out of sync with format " + this.vertexFormat);
        }
        int nextIndex = nextIndices[index];
        this.vertexFormatIndex = nextIndex;
        this.vertexFormatElement = this.vertexFormat.getElement(nextIndex);
    }

    @Override
    public void actinium$setActiveQuadContext(@Nullable VanillaQuadContext context) {
        this.actinium$activeQuadContext = context;
    }

    @Override
    public List<VanillaQuadContext> actinium$consumeQuadContexts() {
        List<VanillaQuadContext> copy = new ArrayList<>(this.actinium$quadContexts);
        this.actinium$quadContexts.clear();
        this.actinium$activeQuadContext = null;
        return copy;
    }

    @Override
    public List<VanillaQuadContext> actinium$copyQuadContexts() {
        return new ArrayList<>(this.actinium$quadContexts);
    }

    @Override
    public void actinium$appendQuadContexts(List<VanillaQuadContext> contexts) {
        this.actinium$quadContexts.addAll(contexts);
    }

    @Override
    public boolean actinium$isDrawing() {
        return this.isDrawing;
    }

    @Override
    public void actinium$discard() {
        this.isDrawing = false;
        this.reset();
    }

    @Override
    public void actinium$projectiveTexCoord(float s, float t, float r, float q) {
        if (this.vertexFormatElement != this.vertexFormat.getElement(this.vertexFormatIndex)) {
            throw new IllegalStateException("BufferBuilder vertex format element is out of sync");
        }
        ProjectiveTexCoordWriter.write(
            this.byteBuffer,
            this.vertexFormat,
            this.vertexFormatIndex,
            this.vertexCount,
            s,
            t,
            r,
            q
        );
        this.actinium$nextVertexFormatIndex();
    }

    /**
     * Packed vertices in the shared eight-int layout the batched TESR and entity draws read.
     *
     * <p>Vanilla stores vertices in the element order and stride of the active format, which is
     * usually not the shared layout. The array is therefore repacked: the attributes the format
     * carries are copied into their fixed slots and the rest stay zero until
     * {@code TessellatorStreamingDrawer.fillMissingAttributes} fills them from GL state. Copying the
     * bytes as if they were already eight-int vertices would shift every attribute.</p>
     */
    @Unique
    private int[] actinium$packedVertices = EMPTY_VERTICES;

    @Unique
    private int actinium$packedVertexCount = -1;

    @Unique
    private static final int[] EMPTY_VERTICES = new int[0];


    @Override
    public boolean isDrawing() {
        return this.isDrawing;
    }

    @Override
    public void setDrawing(boolean drawing) {
        this.isDrawing = drawing;
    }

    @Override
    public int[] getRawBuffer() {
        if (this.actinium$packedVertexCount != this.vertexCount) {
            this.actinium$packVertices();
        }
        return this.actinium$packedVertices;
    }

    @Override
    public int getRawBufferIndex() {
        return this.vertexCount * SharedVertexLayout.VERTEX_INTS;
    }

    @Override
    public int getRawBufferSize() {
        return this.actinium$packedVertices.length;
    }

    @Override
    public void setRawBufferSize(int size) {
        // The shared view is rebuilt from the vertex buffer on every draw, so its size is derived.
    }

    @Override
    public void setRawBuffer(int[] buffer) {
        // The supplied array comes from the caller's own sizing, so it is not a packed view of the
        // current vertices; the next read repacks into it.
        this.actinium$packedVertices = buffer;
        this.actinium$packedVertexCount = -1;
    }

    @Override
    public int getDrawMode() {
        return this.drawMode;
    }

    @Override
    public boolean hasTexture() {
        return this.actinium$hasElement(VertexFormatElement.EnumUsage.UV, 0);
    }

    @Override
    public boolean hasColor() {
        return this.actinium$hasElement(VertexFormatElement.EnumUsage.COLOR, 0);
    }

    @Override
    public boolean hasNormals() {
        return this.actinium$hasElement(VertexFormatElement.EnumUsage.NORMAL, 0);
    }

    @Override
    public boolean hasBrightness() {
        return this.actinium$hasElement(VertexFormatElement.EnumUsage.UV, 1);
    }

    @Override
    public void angelica$reset() {
        this.reset();
        this.actinium$packedVertices = EMPTY_VERTICES;
        this.actinium$packedVertexCount = -1;
    }

    @Unique
    private boolean actinium$hasElement(VertexFormatElement.EnumUsage usage, int index) {
        return SharedVertexLayout.has(this.vertexFormat, usage, index);
    }

    @Unique
    private void actinium$packVertices() {
        final int count = this.vertexCount;
        if (count <= 0) {
            this.actinium$packedVertices = EMPTY_VERTICES;
            this.actinium$packedVertexCount = count;
            return;
        }
        final VertexFormat format = this.vertexFormat;
        if (format == null) {
            throw new IllegalStateException("Packing " + count + " vertices without a vertex format");
        }

        final int[] packed = new int[count * SharedVertexLayout.VERTEX_INTS];
        SharedVertexLayout.pack(this.byteBuffer, format, count, packed);
        this.actinium$packedVertices = packed;
        this.actinium$packedVertexCount = count;
    }
}
