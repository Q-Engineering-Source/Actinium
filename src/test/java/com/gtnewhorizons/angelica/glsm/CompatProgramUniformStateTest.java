package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatProgramUniformStateTest {

    private static final GLContextIdentity CONTEXT = new GLContextIdentity(1L, 0xCA01L, 1L);

    @Test
    void preservesUploadedGenerationsWhenReturningToProgram() {
        CompatProgramUniformState firstProgram = new CompatProgramUniformState(new int[0]);
        CompatProgramUniformState secondProgram = new CompatProgramUniformState(new int[0]);

        assertTrue(firstProgram.needsModelViewUpload(CONTEXT, 4));
        assertTrue(firstProgram.needsFragmentUpload(CONTEXT, 8));
        firstProgram.markModelViewUploaded(CONTEXT, 4);
        firstProgram.markFragmentUploaded(CONTEXT, 8);

        assertTrue(secondProgram.needsModelViewUpload(CONTEXT, 4));
        assertTrue(secondProgram.needsFragmentUpload(CONTEXT, 8));
        secondProgram.markModelViewUploaded(CONTEXT, 4);
        secondProgram.markFragmentUploaded(CONTEXT, 8);

        assertFalse(firstProgram.needsModelViewUpload(CONTEXT, 4));
        assertFalse(firstProgram.needsFragmentUpload(CONTEXT, 8));
        assertFalse(secondProgram.needsModelViewUpload(CONTEXT, 4));
        assertFalse(secondProgram.needsFragmentUpload(CONTEXT, 8));
    }

    @Test
    void invalidatesOnlyChangedUniformCategory() {
        CompatProgramUniformState state = new CompatProgramUniformState(new int[0]);
        state.markModelViewUploaded(CONTEXT, 4);
        state.markFragmentUploaded(CONTEXT, 8);

        assertFalse(state.needsModelViewUpload(CONTEXT, 4));
        assertTrue(state.needsFragmentUpload(CONTEXT, 9));
    }

    @Test
    void tracksRemainingUniformCategoriesIndependently() {
        CompatProgramUniformState state = new CompatProgramUniformState(new int[0]);
        state.markTextureMatrixUploaded(CONTEXT, 2);
        state.markColorUploaded(CONTEXT, 3);
        state.markLightingUploaded(CONTEXT, 4);
        state.markClipPlaneUploaded(CONTEXT, 5);

        assertFalse(state.needsTextureMatrixUpload(CONTEXT, 2));
        assertFalse(state.needsColorUpload(CONTEXT, 3));
        assertFalse(state.needsLightingUpload(CONTEXT, 4));
        assertFalse(state.needsClipPlaneUpload(CONTEXT, 5));

        assertTrue(state.needsTextureMatrixUpload(CONTEXT, 6));
        assertTrue(state.needsColorUpload(CONTEXT, 7));
        assertTrue(state.needsLightingUpload(CONTEXT, 8));
        assertTrue(state.needsClipPlaneUpload(CONTEXT, 9));
    }

    @Test
    void uploadsUninitializedGenerationEvenWhenItIsMinusOne() {
        CompatProgramUniformState state = new CompatProgramUniformState(new int[0]);

        assertTrue(state.needsProjectionUpload(CONTEXT, -1));
        state.markProjectionUploaded(CONTEXT, -1);
        assertFalse(state.needsProjectionUpload(CONTEXT, -1));
    }


}
