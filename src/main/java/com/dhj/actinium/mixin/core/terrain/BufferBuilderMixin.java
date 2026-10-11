package com.dhj.actinium.mixin.core.terrain;

import com.dhj.actinium.render.ProjectiveTexCoordBuffer;
import com.dhj.actinium.render.ProjectiveTexCoordWriter;
import com.dhj.actinium.render.vertex.DirectBufferAddress;
import com.dhj.actinium.render.vertex.FastVertexLayout;
import com.dhj.actinium.render.vertex.VertexWriters;
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
public abstract class BufferBuilderMixin implements BufferBuilderExtension, ProjectiveTexCoordBuffer {
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
    private final List<VanillaQuadContext> actinium$quadContexts = new ArrayList<>();

    @Unique
    private @Nullable VanillaQuadContext actinium$activeQuadContext;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void actinium$init(int bufferSizeIn, CallbackInfo ci) {
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
     * Computes the absolute byte offset of the active element and validates the pending
     * write against the staging buffer limit, reproducing the original bounds semantics:
     * the original methods bounds-check every absolute store, whose contiguous span ends
     * at offset + componentCount * type size. One range check per attribute write keeps
     * that guarantee on the raw-address path, failing before any partial data lands in
     * the buffer.
     *
     * <p>An out-of-range cursor means the active format changed without a reset that
     * re-aligns the cursor; the mismatch is reported here before any store is attempted.
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

    /**
     * Writes the position element through the pre-selected raw writer.
     *
     * <p>The offset arithmetic, type dispatch and translation handling reproduce the
     * original method: the element offset comes from the pre-computed layout, the writer
     * applies the same per-type conversion to the translated coordinates, and the
     * element cursor advances afterwards. The returned builder is always this instance.
     */
    @Overwrite
    public BufferBuilder pos(double x, double y, double z) {
        VertexWriters.forType(this.vertexFormatElement.getType()).writePosition(
            this.actinium$bufferAddress + this.actinium$writeOffset(3),
            x, y, z, this.xOffset, this.yOffset, this.zOffset);
        this.actinium$nextVertexFormatIndex();
        return (BufferBuilder) (Object) this;
    }

    /**
     * Writes the color element through the pre-selected raw writer.
     *
     * <p>Disabled colors keep the original behavior: the method returns without writing
     * and without advancing the element cursor. The byte-wise color layout including its
     * platform-dependent byte order lives in the byte writer; the float and short
     * layouts match the original component order.
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
     * Advances the element cursor along the pre-computed element-advance ring.
     *
     * <p>The ring encodes the original advance result: one step forward with wraparound,
     * repeated past PADDING elements. An out-of-range cursor means the active format
     * changed without going through a reset that re-aligns the cursor; the original code
     * would silently keep drawing through a wrong element, so the mismatch is reported
     * instead of writing into undefined slots.
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
}
