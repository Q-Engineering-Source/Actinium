package com.gtnewhorizons.angelica.glsm.ffp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramUniformStateTest {

    @Test
    void preservesUploadedGenerationsWhenReturningToAProgram() {
        ProgramUniformState firstProgram = new ProgramUniformState();
        ProgramUniformState secondProgram = new ProgramUniformState();

        assertTrue(firstProgram.needsModelViewUpload(4));
        firstProgram.markModelViewUploaded(4);
        assertTrue(secondProgram.needsModelViewUpload(4));
        secondProgram.markModelViewUploaded(4);

        assertFalse(firstProgram.needsModelViewUpload(4));
    }

    @Test
    void invalidatesEachProgramAfterTheGlobalGenerationChanges() {
        ProgramUniformState firstProgram = new ProgramUniformState();
        ProgramUniformState secondProgram = new ProgramUniformState();

        firstProgram.markFragmentUploaded(12);
        secondProgram.markFragmentUploaded(12);

        assertTrue(firstProgram.needsFragmentUpload(13));
        assertTrue(secondProgram.needsFragmentUpload(13));
        firstProgram.markFragmentUploaded(13);
        secondProgram.markFragmentUploaded(13);
        assertFalse(firstProgram.needsFragmentUpload(13));
        assertFalse(secondProgram.needsFragmentUpload(13));
    }

    @Test
    void uploadsAnUninitializedGenerationEvenWhenItIsMinusOne() {
        ProgramUniformState state = new ProgramUniformState();

        assertTrue(state.needsProjectionUpload(-1));
        state.markProjectionUploaded(-1);
        assertFalse(state.needsProjectionUpload(-1));
    }

    @Test
    void failedUploadRemainsDirtyUntilMarkedSuccessful() {
        ProgramUniformState state = new ProgramUniformState();

        assertTrue(state.needsLightingUpload(7));
        assertTrue(state.needsLightingUpload(7));

        state.markLightingUploaded(7);
        assertFalse(state.needsLightingUpload(7));
    }

    @Test
    void tracksNonGenerationUniformValuesPerProgram() {
        ProgramUniformState state = new ProgramUniformState();

        assertTrue(state.needsLightmapUpload(16.0F, 32.0F));
        state.markLightmapUploaded(16.0F, 32.0F);
        assertFalse(state.needsLightmapUpload(16.0F, 32.0F));
        assertTrue(state.needsLightmapUpload(16.0F, 48.0F));

        assertTrue(state.needsLineWidthUpload(2.0F));
        state.markLineWidthUploaded(2.0F);
        assertFalse(state.needsLineWidthUpload(2.0F));

        assertTrue(state.needsViewportUpload(0, 0, 1920, 1080));
        state.markViewportUploaded(0, 0, 1920, 1080);
        assertFalse(state.needsViewportUpload(0, 0, 1920, 1080));
        assertTrue(state.needsViewportUpload(0, 0, 2560, 1440));
        assertTrue(state.needsViewportUpload(100, 50, 1920, 1080));

        assertTrue(state.needsLineStippleUpload(0x0001FFFF));
        state.markLineStippleUploaded(0x0001FFFF);
        assertFalse(state.needsLineStippleUpload(0x0001FFFF));
        assertTrue(state.needsLineStippleUpload(0x00020F0F));
    }
}
