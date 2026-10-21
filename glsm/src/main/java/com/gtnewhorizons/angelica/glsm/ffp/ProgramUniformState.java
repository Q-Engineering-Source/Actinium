package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
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

    boolean needsModelViewUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, MODEL_VIEW, generation);
    }

    void markModelViewUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, MODEL_VIEW, generation);
    }

    boolean needsProjectionUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, PROJECTION, generation);
    }

    void markProjectionUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, PROJECTION, generation);
    }

    boolean needsTextureMatrixUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, TEXTURE_MATRIX, generation);
    }

    void markTextureMatrixUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, TEXTURE_MATRIX, generation);
    }

    boolean needsLightingUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, LIGHTING, generation);
    }

    void markLightingUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, LIGHTING, generation);
    }

    boolean needsFragmentUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, FRAGMENT, generation);
    }

    void markFragmentUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, FRAGMENT, generation);
    }

    boolean needsColorUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, COLOR, generation);
    }

    void markColorUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, COLOR, generation);
    }

    boolean needsNormalUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, NORMAL, generation);
    }

    void markNormalUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, NORMAL, generation);
    }

    boolean needsTexCoordUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, TEX_COORD, generation);
    }

    void markTexCoordUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, TEX_COORD, generation);
    }

    boolean needsTexGenUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, TEX_GEN, generation);
    }

    void markTexGenUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, TEX_GEN, generation);
    }

    boolean needsClipPlaneUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, CLIP_PLANE, generation);
    }

    void markClipPlaneUploaded(GLContextIdentity identity, int generation) {
        markUploaded(identity, CLIP_PLANE, generation);
    }

    boolean needsLightmapUpload(GLContextIdentity identity, float x, float y) {
        return !isInitialized(identity, LIGHTMAP) || lightmapX != x || lightmapY != y;
    }

    void markLightmapUploaded(GLContextIdentity identity, float x, float y) {
        lightmapX = x;
        lightmapY = y;
        markUploaded(identity, LIGHTMAP, 0);
    }

    boolean needsLineWidthUpload(GLContextIdentity identity, float width) {
        return !isInitialized(identity, LINE_WIDTH) || lineWidth != width;
    }

    void markLineWidthUploaded(GLContextIdentity identity, float width) {
        lineWidth = width;
        markUploaded(identity, LINE_WIDTH, 0);
    }

    boolean needsViewportUpload(GLContextIdentity identity, int x, int y, int width, int height) {
        return !isInitialized(identity, VIEWPORT)
            || viewportX != x || viewportY != y || viewportWidth != width || viewportHeight != height;
    }

    void markViewportUploaded(GLContextIdentity identity, int x, int y, int width, int height) {
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        markUploaded(identity, VIEWPORT, 0);
    }

    boolean needsLineStippleUpload(GLContextIdentity identity, int packedFactorPattern) {
        return !isInitialized(identity, LINE_STIPPLE) || lineStipple != packedFactorPattern;
    }

    void markLineStippleUploaded(GLContextIdentity identity, int packedFactorPattern) {
        lineStipple = packedFactorPattern;
        markUploaded(identity, LINE_STIPPLE, 0);
    }
}
