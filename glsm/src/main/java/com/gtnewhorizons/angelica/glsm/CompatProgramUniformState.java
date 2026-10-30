package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.states.GenerationTrackedState;

/**
 * Records compat uniform locations and successful uploads for one linked OpenGL program.
 *
 * <p>Uniform values belong to a program object. Keeping the uploaded generations beside that program preserves valid
 * uploads when rendering returns to a program after another program was active.</p>
 */
final class CompatProgramUniformState extends GenerationTrackedState {

    private static final int MODEL_VIEW = 0;
    private static final int PROJECTION = 1;
    private static final int TEXTURE_MATRIX = 2;
    private static final int FRAGMENT = 3;
    private static final int COLOR = 4;
    private static final int LIGHTING = 5;
    private static final int CLIP_PLANE = 6;

    private final int[] locations;
    private volatile boolean valid = true;

    CompatProgramUniformState(int[] locations) {
        super(7);
        this.locations = locations;
    }

    int[] getLocations() {
        return locations;
    }

    boolean isValid() {
        return valid;
    }

    void invalidate() {
        valid = false;
    }

    boolean needsModelViewUpload(int generation) {
        return needsUpload(MODEL_VIEW, generation);
    }

    void markModelViewUploaded(int generation) {
        if (!valid) return;
        markUploaded(MODEL_VIEW, generation);
    }

    boolean needsProjectionUpload(int generation) {
        return needsUpload(PROJECTION, generation);
    }

    void markProjectionUploaded(int generation) {
        if (!valid) return;
        markUploaded(PROJECTION, generation);
    }

    boolean needsTextureMatrixUpload(int generation) {
        return needsUpload(TEXTURE_MATRIX, generation);
    }

    void markTextureMatrixUploaded(int generation) {
        if (!valid) return;
        markUploaded(TEXTURE_MATRIX, generation);
    }

    boolean needsFragmentUpload(int generation) {
        return needsUpload(FRAGMENT, generation);
    }

    void markFragmentUploaded(int generation) {
        if (!valid) return;
        markUploaded(FRAGMENT, generation);
    }

    boolean needsColorUpload(int generation) {
        return needsUpload(COLOR, generation);
    }

    void markColorUploaded(int generation) {
        if (!valid) return;
        markUploaded(COLOR, generation);
    }

    boolean needsLightingUpload(int generation) {
        return needsUpload(LIGHTING, generation);
    }

    void markLightingUploaded(int generation) {
        if (!valid) return;
        markUploaded(LIGHTING, generation);
    }

    boolean needsClipPlaneUpload(int generation) {
        return needsUpload(CLIP_PLANE, generation);
    }

    void markClipPlaneUploaded(int generation) {
        if (!valid) return;
        markUploaded(CLIP_PLANE, generation);
    }
}
