package com.gtnewhorizons.angelica.glsm.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainVertexArrayDiagnosticsTest {
    @Test
    void recordsGenerationMismatchEvenWhenTheShareGroupMatches() {
        TerrainVertexArrayDiagnostics.TraceToken token = TerrainVertexArrayDiagnostics.createTraceToken(
                true, 85, 120, 220, 31L, 7L);
        TerrainVertexArrayDiagnostics.DeleteScope scope = TerrainVertexArrayDiagnostics.openDeleteScope(
                token, true, 31L, 7L);

        try {
            TerrainVertexArrayDiagnostics.NativeDeleteCall call =
                    TerrainVertexArrayDiagnostics.enterNativeDelete(true, 85, 32L, 7L);

            assertNotNull(call);
            assertEquals("false", call.ownerGenerationMatch());
            assertEquals("true", call.ownerShareGroupMatch());
            assertFalse(call.ownerContextMatched());
            assertEquals(120, token.vertexBufferId());
            assertEquals(220, token.elementBufferId());
        } finally {
            TerrainVertexArrayDiagnostics.closeServiceDelete(scope);
        }
    }

    @Test
    void unknownContextGenerationNeverCountsAsAnOwnerContextMatch() {
        TerrainVertexArrayDiagnostics.TraceToken token = TerrainVertexArrayDiagnostics.createTraceToken(
                true, 86, 121, 221, 0L, 7L);
        TerrainVertexArrayDiagnostics.DeleteScope scope = TerrainVertexArrayDiagnostics.openDeleteScope(
                token, true, 31L, 7L);

        try {
            TerrainVertexArrayDiagnostics.NativeDeleteCall call =
                    TerrainVertexArrayDiagnostics.enterNativeDelete(true, 86, 0L, 7L);

            assertNotNull(call);
            assertEquals("unknown", call.ownerGenerationMatch());
            assertEquals("true", call.ownerShareGroupMatch());
            assertFalse(call.ownerContextMatched());
        } finally {
            TerrainVertexArrayDiagnostics.closeServiceDelete(scope);
        }
    }

    @Test
    void disabledDiagnosticsCreateNoTokenOrDeleteScope() {
        TerrainVertexArrayDiagnostics.TraceToken token = TerrainVertexArrayDiagnostics.createTraceToken(
                false, 87, 122, 222, 31L, 7L);

        assertNull(token);
        assertNull(TerrainVertexArrayDiagnostics.openDeleteScope(token, false, 31L, 7L));
        assertNull(TerrainVertexArrayDiagnostics.enterNativeDelete(false, 87, 31L, 7L));
    }

    @Test
    void nativeDeleteMatchesOnlyOneCallInTheActiveScopeAndScopeClosesCleanly() {
        TerrainVertexArrayDiagnostics.TraceToken token = TerrainVertexArrayDiagnostics.createTraceToken(
                true, 88, 123, 223, 31L, 7L);
        TerrainVertexArrayDiagnostics.DeleteScope scope = TerrainVertexArrayDiagnostics.openDeleteScope(
                token, true, 31L, 7L);

        try {
            TerrainVertexArrayDiagnostics.NativeDeleteCall otherVertexArray =
                    TerrainVertexArrayDiagnostics.enterNativeDelete(true, 89, 31L, 7L);
            TerrainVertexArrayDiagnostics.NativeDeleteCall first =
                    TerrainVertexArrayDiagnostics.enterNativeDelete(true, 88, 31L, 7L);
            TerrainVertexArrayDiagnostics.NativeDeleteCall duplicate =
                    TerrainVertexArrayDiagnostics.enterNativeDelete(true, 88, 31L, 7L);

            assertNull(otherVertexArray);
            assertNotNull(first);
            assertNull(duplicate);
        } finally {
            TerrainVertexArrayDiagnostics.closeServiceDelete(scope);
        }
        assertNull(TerrainVertexArrayDiagnostics.enterNativeDelete(true, 88, 31L, 7L));
    }
}
