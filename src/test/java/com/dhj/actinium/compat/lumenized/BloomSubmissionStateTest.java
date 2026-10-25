package com.dhj.actinium.compat.lumenized;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomSubmissionStateTest {
    @Test
    void tracksEmptyAndRenderableGroupsIndependently() {
        BloomSubmissionState.beginGroup();
        assertFalse(BloomSubmissionState.lastGroupHasContent());
        BloomSubmissionState.endGroup();
        assertFalse(BloomSubmissionState.lastGroupHasContent());

        BloomSubmissionState.beginGroup();
        BloomSubmissionState.markRenderable();
        BloomSubmissionState.endGroup();
        assertTrue(BloomSubmissionState.lastGroupHasContent());
    }

    @Test
    void nestedGroupsRestoreTheOuterSubmissionState() {
        BloomSubmissionState.beginGroup();
        BloomSubmissionState.markRenderable();

        BloomSubmissionState.beginGroup();
        BloomSubmissionState.endGroup();
        assertFalse(BloomSubmissionState.lastGroupHasContent());

        BloomSubmissionState.endGroup();
        assertTrue(BloomSubmissionState.lastGroupHasContent());
    }
}
