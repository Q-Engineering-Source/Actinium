package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.states.GenerationTrackedState;

/**
 * Records the FFP uniform state successfully uploaded to one linked program.
 *
 * <p>Uniform values belong to a program object in OpenGL. Keeping these markers beside the program allows a previously
 * used variant to retain its uploaded values while another variant is active.</p>
 */
final class ProgramUniformState extends GenerationTrackedState {

    private static final int MODEL_VIEW = 0;
    private static final int PROJECTION = 1;
    private static final int TEXTURE_MATRIX = 2;
    private static final int LIGHTING = 3;
    private static final int FRAGMENT = 4;
    private static final int COLOR = 5;
    private static final int NORMAL = 6;
    private static final int TEX_COORD = 7;
    private static final int TEX_GEN = 8;
    private static final int CLIP_PLANE = 9;
    private static final int LIGHTMAP = 10;
    private static final int LINE_WIDTH = 11;
    private static final int VIEWPORT = 12;
    private static final int LINE_STIPPLE = 13;

    private float lightmapX;
    private float lightmapY;
    private float lineWidth;
    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private int lineStipple;

    ProgramUniformState() {
        super(14);
    }

    boolean needsModelViewUpload(int generation) {
        return needsUpload(MODEL_VIEW, generation);
    }

    void markModelViewUploaded(int generation) {
        markUploaded(MODEL_VIEW, generation);
    }

    boolean needsProjectionUpload(int generation) {
        return needsUpload(PROJECTION, generation);
    }

    void markProjectionUploaded(int generation) {
        markUploaded(PROJECTION, generation);
    }

    boolean needsTextureMatrixUpload(int generation) {
        return needsUpload(TEXTURE_MATRIX, generation);
    }

    void markTextureMatrixUploaded(int generation) {
        markUploaded(TEXTURE_MATRIX, generation);
    }

    boolean needsLightingUpload(int generation) {
        return needsUpload(LIGHTING, generation);
    }

    void markLightingUploaded(int generation) {
        markUploaded(LIGHTING, generation);
    }

    boolean needsFragmentUpload(int generation) {
        return needsUpload(FRAGMENT, generation);
    }

    void markFragmentUploaded(int generation) {
        markUploaded(FRAGMENT, generation);
    }

    boolean needsColorUpload(int generation) {
        return needsUpload(COLOR, generation);
    }

    void markColorUploaded(int generation) {
        markUploaded(COLOR, generation);
    }

    boolean needsNormalUpload(int generation) {
        return needsUpload(NORMAL, generation);
    }

    void markNormalUploaded(int generation) {
        markUploaded(NORMAL, generation);
    }

    boolean needsTexCoordUpload(int generation) {
        return needsUpload(TEX_COORD, generation);
    }

    void markTexCoordUploaded(int generation) {
        markUploaded(TEX_COORD, generation);
    }

    boolean needsTexGenUpload(int generation) {
        return needsUpload(TEX_GEN, generation);
    }

    void markTexGenUploaded(int generation) {
        markUploaded(TEX_GEN, generation);
    }

    boolean needsClipPlaneUpload(int generation) {
        return needsUpload(CLIP_PLANE, generation);
    }

    void markClipPlaneUploaded(int generation) {
        markUploaded(CLIP_PLANE, generation);
    }

    boolean needsLightmapUpload(float x, float y) {
        return !isInitialized(LIGHTMAP) || lightmapX != x || lightmapY != y;
    }

    void markLightmapUploaded(float x, float y) {
        lightmapX = x;
        lightmapY = y;
        markUploaded(LIGHTMAP, 0);
    }

    boolean needsLineWidthUpload(float width) {
        return !isInitialized(LINE_WIDTH) || lineWidth != width;
    }

    void markLineWidthUploaded(float width) {
        lineWidth = width;
        markUploaded(LINE_WIDTH, 0);
    }

    boolean needsViewportUpload(int x, int y, int width, int height) {
        return !isInitialized(VIEWPORT)
            || viewportX != x || viewportY != y || viewportWidth != width || viewportHeight != height;
    }

    void markViewportUploaded(int x, int y, int width, int height) {
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        markUploaded(VIEWPORT, 0);
    }

    boolean needsLineStippleUpload(int packedFactorPattern) {
        return !isInitialized(LINE_STIPPLE) || lineStipple != packedFactorPattern;
    }

    void markLineStippleUploaded(int packedFactorPattern) {
        lineStipple = packedFactorPattern;
        markUploaded(LINE_STIPPLE, 0);
    }
}
