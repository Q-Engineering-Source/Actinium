package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
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

    boolean needsModelViewUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, MODEL_VIEW, generation);
    }

    void markModelViewUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, MODEL_VIEW, generation);
    }

    boolean needsProjectionUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, PROJECTION, generation);
    }

    void markProjectionUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, PROJECTION, generation);
    }

    boolean needsTextureMatrixUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, TEXTURE_MATRIX, generation);
    }

    void markTextureMatrixUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, TEXTURE_MATRIX, generation);
    }

    boolean needsFragmentUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, FRAGMENT, generation);
    }

    void markFragmentUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, FRAGMENT, generation);
    }

    boolean needsColorUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, COLOR, generation);
    }

    void markColorUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, COLOR, generation);
    }

    boolean needsLightingUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, LIGHTING, generation);
    }

    void markLightingUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, LIGHTING, generation);
    }

    boolean needsClipPlaneUpload(GLContextIdentity identity, int generation) {
        return needsUpload(identity, CLIP_PLANE, generation);
    }

    void markClipPlaneUploaded(GLContextIdentity identity, int generation) {
        if (!valid) return;
        markUploaded(identity, CLIP_PLANE, generation);
    }
}
