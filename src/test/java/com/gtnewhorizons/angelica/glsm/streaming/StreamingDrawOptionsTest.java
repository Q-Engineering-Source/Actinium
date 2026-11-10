package com.gtnewhorizons.angelica.glsm.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingDrawOptionsTest {
    @Test
    void preservesExternalProgramOnlyInsideTheScope() {
        assertFalse(StreamingDrawOptions.isExternalProgramPreserved());
        try (StreamingDrawOptions.Scope ignored = StreamingDrawOptions.preserveExternalProgram()) {
            assertTrue(StreamingDrawOptions.isExternalProgramPreserved());
        }
        assertFalse(StreamingDrawOptions.isExternalProgramPreserved());
    }

    @Test
    void nestedScopesRestoreTheOuterOption() {
        try (StreamingDrawOptions.Scope outer = StreamingDrawOptions.preserveExternalProgram()) {
            try (StreamingDrawOptions.Scope inner = StreamingDrawOptions.preserveExternalProgram()) {
                assertTrue(StreamingDrawOptions.isExternalProgramPreserved());
            }
            assertTrue(StreamingDrawOptions.isExternalProgramPreserved());
        }
        assertFalse(StreamingDrawOptions.isExternalProgramPreserved());
    }
}
