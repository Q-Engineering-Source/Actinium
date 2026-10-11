package dhj.embeddedt.embeddium.api.shader.buffer;

import net.minecraft.block.state.IBlockState;

import javax.annotation.Nullable;

/** Immutable shader metadata for one vanilla-format quad. */
public final class VanillaQuadContext {
    private final int localPosX;
    private final int localPosY;
    private final int localPosZ;
    private final int blockStateId;
    private final short renderType;
    private final byte lightValue;
    /** Keeps a constituent block state until its shader ID can be resolved for the active pack. */
    private final @Nullable IBlockState deferredBlockState;
    /** Preserves an explicit shader material override while deferred state data is resolved. */
    private final boolean blockStateIdOverridden;

    public VanillaQuadContext(int localPosX, int localPosY, int localPosZ, int blockStateId, short renderType, byte lightValue) {
        this(localPosX, localPosY, localPosZ, blockStateId, renderType, lightValue, null, false);
    }

    /** Creates a context whose block ID and light value will be resolved during chunk conversion. */
    public VanillaQuadContext(int localPosX, int localPosY, int localPosZ, IBlockState deferredBlockState, short renderType) {
        this(localPosX, localPosY, localPosZ, -1, renderType, (byte) 0, deferredBlockState, false);
    }

    private VanillaQuadContext(
            int localPosX,
            int localPosY,
            int localPosZ,
            int blockStateId,
            short renderType,
            byte lightValue,
            @Nullable IBlockState deferredBlockState,
            boolean blockStateIdOverridden
    ) {
        this.localPosX = localPosX;
        this.localPosY = localPosY;
        this.localPosZ = localPosZ;
        this.blockStateId = blockStateId;
        this.renderType = renderType;
        this.lightValue = lightValue;
        this.deferredBlockState = deferredBlockState;
        this.blockStateIdOverridden = blockStateIdOverridden;
    }

    public int localPosX() {
        return this.localPosX;
    }

    public int localPosY() {
        return this.localPosY;
    }

    public int localPosZ() {
        return this.localPosZ;
    }

    public int blockStateId() {
        return this.blockStateId;
    }

    /** Returns the block state awaiting shader ID and light resolution, if present. */
    @Nullable
    public IBlockState deferredBlockState() {
        return this.deferredBlockState;
    }

    /** Applies an explicit shader material override to this quad context. */
    public VanillaQuadContext withBlockStateId(int blockStateId) {
        if (this.deferredBlockState == null && this.blockStateId == blockStateId) {
            return this;
        }

        return new VanillaQuadContext(
                this.localPosX,
                this.localPosY,
                this.localPosZ,
                blockStateId,
                this.renderType,
                this.lightValue,
                this.deferredBlockState,
                true
        );
    }

    /** Resolves this context against the active world slice while keeping explicit ID overrides. */
    public VanillaQuadContext withResolvedBlockState(int blockStateId, byte lightValue) {
        return new VanillaQuadContext(
                this.localPosX,
                this.localPosY,
                this.localPosZ,
                this.blockStateIdOverridden ? this.blockStateId : blockStateId,
                this.renderType,
                lightValue,
                null,
                false
        );
    }

    public short renderType() {
        return this.renderType;
    }

    public byte lightValue() {
        return this.lightValue;
    }
}
