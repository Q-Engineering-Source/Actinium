package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatProgramUniformStateTest {

    @Test
    void preservesUploadedGenerationsWhenReturningToProgram() {
        CompatProgramUniformState firstProgram = new CompatProgramUniformState(new int[0]);
        CompatProgramUniformState secondProgram = new CompatProgramUniformState(new int[0]);

        assertTrue(firstProgram.needsModelViewUpload(4));
        assertTrue(firstProgram.needsFragmentUpload(8));
        firstProgram.markModelViewUploaded(4);
        firstProgram.markFragmentUploaded(8);

        assertTrue(secondProgram.needsModelViewUpload(4));
        assertTrue(secondProgram.needsFragmentUpload(8));
        secondProgram.markModelViewUploaded(4);
        secondProgram.markFragmentUploaded(8);

        assertFalse(firstProgram.needsModelViewUpload(4));
        assertFalse(firstProgram.needsFragmentUpload(8));
        assertFalse(secondProgram.needsModelViewUpload(4));
        assertFalse(secondProgram.needsFragmentUpload(8));
    }

    @Test
    void invalidatesOnlyChangedUniformCategory() {
        CompatProgramUniformState state = new CompatProgramUniformState(new int[0]);
        state.markModelViewUploaded(4);
        state.markFragmentUploaded(8);

        assertFalse(state.needsModelViewUpload(4));
        assertTrue(state.needsFragmentUpload(9));
    }

    @Test
    void tracksRemainingUniformCategoriesIndependently() {
        CompatProgramUniformState state = new CompatProgramUniformState(new int[0]);
        state.markTextureMatrixUploaded(2);
        state.markColorUploaded(3);
        state.markLightingUploaded(4);
        state.markClipPlaneUploaded(5);

        assertFalse(state.needsTextureMatrixUpload(2));
        assertFalse(state.needsColorUpload(3));
        assertFalse(state.needsLightingUpload(4));
        assertFalse(state.needsClipPlaneUpload(5));

        assertTrue(state.needsTextureMatrixUpload(6));
        assertTrue(state.needsColorUpload(7));
        assertTrue(state.needsLightingUpload(8));
        assertTrue(state.needsClipPlaneUpload(9));
    }

    @Test
    void uploadsUninitializedGenerationEvenWhenItIsMinusOne() {
        CompatProgramUniformState state = new CompatProgramUniformState(new int[0]);

        assertTrue(state.needsProjectionUpload(-1));
        state.markProjectionUploaded(-1);
        assertFalse(state.needsProjectionUpload(-1));
    }


}
