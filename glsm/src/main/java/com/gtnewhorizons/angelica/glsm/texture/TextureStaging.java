package com.gtnewhorizons.angelica.glsm.texture;

import java.nio.ByteBuffer;

/**
 * Native block a render backend hands to a producer so it can fill texture pixels in CPU memory
 * before they reach the driver in a single upload.
 *
 * <p>{@code beginTextureStaging}, {@code commitTextureStaging}, {@code abandonTextureStaging} and
 * {@code trimTextureStaging} run only on the render thread that owns the GL context. The pixel writes
 * into {@link #buffer()} may run on any thread as long as the producer joins them before it commits.
 * A block is released exactly once, through {@code commitTextureStaging} or
 * {@code abandonTextureStaging} of the backend that issued it; a second release fails fast.
 * {@code trimTextureStaging} frees only blocks already returned to the cache and never touches a block
 * a caller still holds.</p>
 */
public interface TextureStaging {

    /**
     * Block the caller fills. The upload covers the whole capacity starting at the first byte, so a
     * producer may leave the buffer position wherever its last write ended.
     */
    ByteBuffer buffer();

    /** True when {@link #buffer()} already holds BGRA bytes, so the caller need not swap channels. */
    boolean bgra();

    /** Mip level the staged region belongs to. */
    int level();
}
