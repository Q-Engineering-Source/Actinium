package com.dhj.actinium.compat.voxelmap;

import com.mamiyaotaru.voxelmap.util.GLShim;
import com.mamiyaotaru.voxelmap.util.GLUtils;

/**
 * Compatibility bridge that routes the VoxelMap minimap (mod id {@code voxelmap}) onto the plain
 * CPU-texture render path.
 *
 * <p>VoxelMap picks its pipeline from {@code GLUtils.hasAlphaBits} / {@code fboEnabled} probed at
 * class-load time. Under Actinium the LWJGL2 compatibility layer creates the window without alpha
 * bits, so VoxelMap selects the legacy {@code EXT_framebuffer_object} path, which renders a black
 * minimap on the core profile; {@link #forceCpuMinimapPath()} re-selects the direct CPU-texture
 * path. That path requests {@code GL_LINEAR_MIPMAP_LINEAR} and relies on {@code GL_GENERATE_MIPMAP},
 * which core profiles ignore, so the incomplete texture samples opaque black and the filter is
 * downgraded to plain {@code GL_LINEAR}.</p>
 */
public final class VoxelMapCompat {
    /** {@code GL_TEXTURE_MIN_FILTER} - texture parameter name for minification filtering. */
    private static final int GL_TEXTURE_MIN_FILTER = 10241;
    /** {@code GL_LINEAR_MIPMAP_LINEAR} - minification filter that requires a full mipmap chain. */
    private static final int GL_LINEAR_MIPMAP_LINEAR = 9987;
    /** {@code GL_LINEAR} - plain linear minification filter that works without mipmaps. */
    private static final int GL_LINEAR = 9729;

    /**
     * True while {@code Map.renderMap} has scoped the minimap alpha clear to the map rectangle
     * (see {@code MixinVoxelMapRenderMap}). Shared between the renderMap and GLShim mixins so the
     * mixin classes themselves stay free of cross-class state.
     */
    public static boolean mapScissorActive;

    private VoxelMapCompat() {
    }

    /**
     * Forces VoxelMap onto the direct CPU-texture minimap path regardless of the probed
     * framebuffer capabilities. Safe on any context: this is the path VoxelMap uses by default
     * whenever the framebuffer has alpha bits, and the FBO path is never required by it.
     */
    public static void forceCpuMinimapPath() {
        GLUtils.hasAlphaBits = true;
        GLUtils.fboEnabled = false;
    }

    /**
     * Whether a texture parameter update selects the mipmap-based minification filter that cannot
     * work in core-profile contexts (no {@code GL_GENERATE_MIPMAP}, so sampling returns opaque
     * black); the update must be downgraded to plain linear filtering.
     */
    public static boolean needsLinearMinFilter(int pname, int param) {
        return pname == GL_TEXTURE_MIN_FILTER && param == GL_LINEAR_MIPMAP_LINEAR;
    }

    /** The plain linear minification filter used as the downgrade target. */
    public static int linearMinFilter() {
        return GL_LINEAR;
    }
}
