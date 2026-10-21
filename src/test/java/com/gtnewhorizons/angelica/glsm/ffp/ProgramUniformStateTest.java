package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramUniformStateTest {

    private static final GLContextIdentity CONTEXT = new GLContextIdentity(1L, 0xFF01L, 1L);

    @Test
    void preservesUploadedGenerationsWhenReturningToAProgram() {
        ProgramUniformState firstProgram = new ProgramUniformState();
        ProgramUniformState secondProgram = new ProgramUniformState();

        assertTrue(firstProgram.needsModelViewUpload(CONTEXT, 4));
        firstProgram.markModelViewUploaded(CONTEXT, 4);
        assertTrue(secondProgram.needsModelViewUpload(CONTEXT, 4));
        secondProgram.markModelViewUploaded(CONTEXT, 4);

        assertFalse(firstProgram.needsModelViewUpload(CONTEXT, 4));
    }

    @Test
    void invalidatesEachProgramAfterTheGlobalGenerationChanges() {
        ProgramUniformState firstProgram = new ProgramUniformState();
        ProgramUniformState secondProgram = new ProgramUniformState();

        firstProgram.markFragmentUploaded(CONTEXT, 12);
        secondProgram.markFragmentUploaded(CONTEXT, 12);

        assertTrue(firstProgram.needsFragmentUpload(CONTEXT, 13));
        assertTrue(secondProgram.needsFragmentUpload(CONTEXT, 13));
        firstProgram.markFragmentUploaded(CONTEXT, 13);
        secondProgram.markFragmentUploaded(CONTEXT, 13);
        assertFalse(firstProgram.needsFragmentUpload(CONTEXT, 13));
        assertFalse(secondProgram.needsFragmentUpload(CONTEXT, 13));
    }

    @Test
    void uploadsAnUninitializedGenerationEvenWhenItIsMinusOne() {
        ProgramUniformState state = new ProgramUniformState();

        assertTrue(state.needsProjectionUpload(CONTEXT, -1));
        state.markProjectionUploaded(CONTEXT, -1);
        assertFalse(state.needsProjectionUpload(CONTEXT, -1));
    }

    @Test
    void failedUploadRemainsDirtyUntilMarkedSuccessful() {
        ProgramUniformState state = new ProgramUniformState();

        assertTrue(state.needsLightingUpload(CONTEXT, 7));
        assertTrue(state.needsLightingUpload(CONTEXT, 7));

        state.markLightingUploaded(CONTEXT, 7);
        assertFalse(state.needsLightingUpload(CONTEXT, 7));
    }

    @Test
    void tracksNonGenerationUniformValuesPerProgram() {
        ProgramUniformState state = new ProgramUniformState();

        assertTrue(state.needsLightmapUpload(CONTEXT, 16.0F, 32.0F));
        state.markLightmapUploaded(CONTEXT, 16.0F, 32.0F);
        assertFalse(state.needsLightmapUpload(CONTEXT, 16.0F, 32.0F));
        assertTrue(state.needsLightmapUpload(CONTEXT, 16.0F, 48.0F));

        assertTrue(state.needsLineWidthUpload(CONTEXT, 2.0F));
        state.markLineWidthUploaded(CONTEXT, 2.0F);
        assertFalse(state.needsLineWidthUpload(CONTEXT, 2.0F));

        assertTrue(state.needsViewportUpload(CONTEXT, 0, 0, 1920, 1080));
        state.markViewportUploaded(CONTEXT, 0, 0, 1920, 1080);
        assertFalse(state.needsViewportUpload(CONTEXT, 0, 0, 1920, 1080));
        assertTrue(state.needsViewportUpload(CONTEXT, 0, 0, 2560, 1440));
        assertTrue(state.needsViewportUpload(CONTEXT, 100, 50, 1920, 1080));

        assertTrue(state.needsLineStippleUpload(CONTEXT, 0x0001FFFF));
        state.markLineStippleUploaded(CONTEXT, 0x0001FFFF);
        assertFalse(state.needsLineStippleUpload(CONTEXT, 0x0001FFFF));
        assertTrue(state.needsLineStippleUpload(CONTEXT, 0x00020F0F));
    }

    @Test
    void keepsUploadMarkersIndependentForEachContextIdentity() {
        final ProgramUniformState state = new ProgramUniformState();
        final GLContextIdentity contextB = new GLContextIdentity(2L, 0xFF02L, CONTEXT.shareGroupId());

        state.markModelViewUploaded(CONTEXT, 4);

        assertFalse(state.needsModelViewUpload(CONTEXT, 4));
        assertTrue(state.needsModelViewUpload(contextB, 4));

        state.markModelViewUploaded(contextB, 4);
        state.retireContext(CONTEXT);

        assertFalse(state.needsModelViewUpload(contextB, 4));
        assertTrue(state.needsModelViewUpload(CONTEXT, 4));
    }
}
