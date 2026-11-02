package com.gtnewhorizons.angelica.glsm.ffp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the client array bookkeeping without a GL context: conventional arrays and generic attributes are
 * kept apart, the generic pointer shadows the conventional one only while it is enabled, the query pnames
 * answer the conventional array, and a pointer whose buffer was deleted stops counting as live.
 */
class VAOManagerClientArraysTest {
    private static final int VAO = 3;
    private static final ByteBuffer CLIENT_DATA = ByteBuffer.allocateDirect(256);
    private static final int POSITION_LOCATION = 0;
    private static final int NORMAL_LOCATION = 4;

    private final VAOManager vaos = new VAOManager();

    @BeforeEach
    void setUp() {
        vaos.init(0);
        vaos.onBindVertexArrayPre(VAO);
    }

    @Test
    void genericPointerShadowsConventionalArrayOnlyWhileEnabled() {
        vaos.setConventional(VAOManager.CONV_POSITION, 3, GL11.GL_FLOAT, false, 12, CLIENT_DATA);
        vaos.setConventionalEnabled(VAOManager.CONV_POSITION, true);

        assertEquals(12, vaos.attrib(POSITION_LOCATION).effectiveStride());
        assertTrue(vaos.isAttribEnabled(POSITION_LOCATION));
        assertTrue(vaos.hasAnyClientSideEnabledAttrib());

        assertFalse(vaos.setAttribute(POSITION_LOCATION, 4, GL11.GL_FLOAT, false, 16, 0L, 9),
            "a generic pointer at a conventionally enabled location only takes over once it is enabled itself");
        assertEquals(12, vaos.attrib(POSITION_LOCATION).effectiveStride());

        assertTrue(vaos.enableAttribute(POSITION_LOCATION), "enabling the generic attribute has to re-point the location");
        assertEquals(16, vaos.attrib(POSITION_LOCATION).effectiveStride());
        assertEquals(9, vaos.attrib(POSITION_LOCATION).vboId);
        assertFalse(vaos.hasAnyClientSideEnabledAttrib(), "the shadowing attribute is buffer backed");

        assertTrue(vaos.disableAttribute(POSITION_LOCATION), "disabling the generic attribute has to re-point the location");
        assertEquals(3, vaos.attrib(POSITION_LOCATION).size);
        assertEquals(12, vaos.attrib(POSITION_LOCATION).effectiveStride());
        assertEquals(CLIENT_DATA.capacity(), vaos.attrib(POSITION_LOCATION).clientPointer.capacity());
        assertTrue(vaos.hasAnyClientSideEnabledAttrib());

        vaos.setConventionalEnabled(VAOManager.CONV_POSITION, false);
        assertFalse(vaos.isAttribEnabled(POSITION_LOCATION));
        assertFalse(vaos.hasAnyClientSideEnabledAttrib());
    }

    @Test
    void conventionalSlotKeepsItsOwnPointerNextToAGenericOne() {
        vaos.setConventional(VAOManager.CONV_NORMAL, 3, GL11.GL_BYTE, true, 6, CLIENT_DATA);
        vaos.setAttribute(NORMAL_LOCATION, 3, GL11.GL_SHORT, false, 12, 0L, 4);
        vaos.enableAttribute(NORMAL_LOCATION);

        assertEquals(12, vaos.attrib(NORMAL_LOCATION).effectiveStride());
        final VAOManager.Attrib conventional = vaos.conventional(VAOManager.CONV_NORMAL);
        assertNotNull(conventional);
        assertEquals(6, conventional.effectiveStride(), "the conventional pointer keeps its own stride");
        assertEquals(GL11.GL_BYTE, conventional.type);
        assertNotNull(conventional.clientPointer);
        assertNotSame(conventional, vaos.attrib(NORMAL_LOCATION));
    }

    @Test
    void clientArrayQueriesAnswerTheSpecifiedConventionalArray() {
        vaos.setConventional(VAOManager.CONV_POSITION, 2, GL11.GL_SHORT, false, 10, 24L, 5);

        assertEquals(2, query(GL11.GL_VERTEX_ARRAY_SIZE, 0));
        assertEquals(GL11.GL_SHORT, query(GL11.GL_VERTEX_ARRAY_TYPE, 0));
        assertEquals(10, query(GL11.GL_VERTEX_ARRAY_STRIDE, 0));
        assertEquals(5, query(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING, 0));

        assertEquals(4, query(GL11.GL_COLOR_ARRAY_SIZE, 0), "an array that was never specified reports four components");
        assertEquals(3, query(GL14.GL_SECONDARY_COLOR_ARRAY_SIZE, 0));
        assertEquals(GL11.GL_FLOAT, query(GL11.GL_COLOR_ARRAY_TYPE, 0));
        assertEquals(0, query(GL11.GL_EDGE_FLAG_ARRAY_STRIDE, 0));
    }

    @Test
    void textureCoordArrayQueriesFollowTheClientActiveUnit() {
        vaos.setConventional(VAOManager.CONV_TEX0 + 1, 2, GL11.GL_FLOAT, false, 8, CLIENT_DATA);
        vaos.setConventional(VAOManager.CONV_TEX0 + 3, 4, GL11.GL_FLOAT, false, 16, CLIENT_DATA);

        assertEquals(2, query(GL11.GL_TEXTURE_COORD_ARRAY_SIZE, 1));
        assertEquals(8, query(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE, 1));
        assertEquals(4, query(GL11.GL_TEXTURE_COORD_ARRAY_SIZE, 3));
        assertEquals(16, query(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE, 3));
        assertEquals(0, query(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE, 0), "unit 0 has no texcoord array of its own");
    }

    @Test
    void enableCapsMapToTheirConventionalSlot() {
        assertEquals(VAOManager.CONV_POSITION, VAOManager.pnameToConventional(GL11.GL_VERTEX_ARRAY, 0));
        assertEquals(VAOManager.CONV_NORMAL, VAOManager.pnameToConventional(GL11.GL_NORMAL_ARRAY, 0));
        assertEquals(VAOManager.CONV_COLOR, VAOManager.pnameToConventional(GL11.GL_COLOR_ARRAY, 0));
        assertEquals(VAOManager.CONV_INDEX, VAOManager.pnameToConventional(GL11.GL_INDEX_ARRAY, 0));
        assertEquals(VAOManager.CONV_EDGE_FLAG, VAOManager.pnameToConventional(GL11.GL_EDGE_FLAG_ARRAY, 0));
        assertEquals(VAOManager.CONV_FOG_COORD, VAOManager.pnameToConventional(GL14.GL_FOG_COORDINATE_ARRAY, 0));
        assertEquals(VAOManager.CONV_SECONDARY_COLOR, VAOManager.pnameToConventional(GL14.GL_SECONDARY_COLOR_ARRAY, 0));
        assertEquals(VAOManager.CONV_TEX0 + 2, VAOManager.pnameToConventional(GL11.GL_TEXTURE_COORD_ARRAY, 2));
        assertEquals(-1, VAOManager.pnameToConventional(GL11.GL_BLEND, 0));
    }

    @Test
    void arraysWithoutAnAttributeLocationAreTrackedButNotAssigned() {
        vaos.setConventional(VAOManager.CONV_EDGE_FLAG, 1, GL11.GL_UNSIGNED_BYTE, false, 4, CLIENT_DATA);
        vaos.setConventional(VAOManager.CONV_FOG_COORD, 1, GL11.GL_FLOAT, false, 4, CLIENT_DATA);
        assertEquals(-1, VAOManager.conventionalLocation(VAOManager.CONV_EDGE_FLAG));

        vaos.setConventionalEnabled(VAOManager.CONV_EDGE_FLAG, true);
        assertTrue(vaos.isConventionalEnabled(VAOManager.CONV_EDGE_FLAG));
        for (int location = 0; location < VAOManager.MAX_ATTRIBS; location++) {
            assertFalse(vaos.isAttribEnabled(location), "edge flag and fog coordinate arrays have no attribute to enable");
        }
        assertEquals(4, query(GL11.GL_EDGE_FLAG_ARRAY_STRIDE, 0));
        assertEquals(GL11.GL_FLOAT, query(GL14.GL_FOG_COORDINATE_ARRAY_TYPE, 0));
    }

    @Test
    void deletedBufferDetachesLivePointersAndKillsSavedSnapshots() {
        vaos.setAttribute(POSITION_LOCATION, 3, GL11.GL_FLOAT, false, 12, 0L, 7);
        final VAOManager.Attrib attrib = vaos.attrib(POSITION_LOCATION);
        assertTrue(vaos.isBufferLive(attrib));
        vaos.saveClientArrays(0, GL11.GL_CLIENT_VERTEX_ARRAY_BIT, 4, 7);
        final VAOManager.ClientArrays saved = vaos.savedClientArrays(0);
        assertTrue(vaos.isSavedVboLive(saved));

        vaos.onDeleteBuffer(7);
        assertEquals(0, attrib.vboId, "a deleted buffer is detached from the live pointer");
        assertFalse(vaos.isSavedVboLive(saved), "the saved snapshot keeps the dead buffer so a restore can skip its pointers");
    }

    @Test
    void otherVertexArraysKeepTheDeadBufferUntilTheDeletionHistoryIsPruned() {
        final VAOManager pruned = new VAOManager(2);
        pruned.init(0);
        pruned.onBindVertexArrayPre(1);
        pruned.setAttribute(POSITION_LOCATION, 3, GL11.GL_FLOAT, false, 12, 0L, 7);
        pruned.setAttribute(NORMAL_LOCATION, 3, GL11.GL_FLOAT, false, 12, 0L, 8);
        pruned.onBindVertexArrayPre(2);
        pruned.setAttribute(POSITION_LOCATION, 3, GL11.GL_FLOAT, false, 12, 0L, 7);

        pruned.onDeleteBuffer(7);
        pruned.onBindVertexArrayPre(1);
        final VAOManager.Attrib deleted = pruned.attrib(POSITION_LOCATION);
        final VAOManager.Attrib live = pruned.attrib(NORMAL_LOCATION);
        assertEquals(7, deleted.vboId, "a vertex array that is not bound keeps its pointer to the deleted buffer");
        assertFalse(pruned.isBufferLive(deleted));

        pruned.onDeleteBuffer(9);
        assertFalse(pruned.isBufferLive(deleted), "pruning the deletion history must not resurrect a dead pointer");
        assertTrue(pruned.isBufferLive(live));

        pruned.setAttribute(POSITION_LOCATION, 3, GL11.GL_FLOAT, false, 12, 0L, 7);
        assertTrue(pruned.isBufferLive(pruned.attrib(POSITION_LOCATION)), "a pointer recorded after the prune is live again");
    }

    @Test
    void pushSnapshotCapturesBindingsOnlyWithTheVertexArrayBit() {
        vaos.setConventional(VAOManager.CONV_POSITION, 3, GL11.GL_FLOAT, false, 12, 0L, 6);
        vaos.onBindEBO(11);
        vaos.saveClientArrays(0, GL11.GL_CLIENT_VERTEX_ARRAY_BIT, 4, 6);

        final VAOManager.ClientArrays saved = vaos.savedClientArrays(0);
        assertNotNull(saved);
        assertEquals(GL11.GL_CLIENT_VERTEX_ARRAY_BIT, vaos.savedClientAttribMask(0));
        assertEquals(4, saved.boundVAO);
        assertEquals(6, saved.boundVBO);
        assertEquals(11, saved.boundEBO);
        assertTrue(vaos.isSavedVboLive(saved));
        assertTrue(vaos.isSavedEboLive(saved));

        vaos.saveClientArrays(1, GL11.GL_CLIENT_PIXEL_STORE_BIT, 4, 6);
        assertEquals(GL11.GL_CLIENT_PIXEL_STORE_BIT, vaos.savedClientAttribMask(1));
        assertEquals(0, vaos.savedClientArrays(1).boundVAO, "a push without the vertex array bit stores no vertex array state");
    }

    @Test
    void savedSnapshotStopsRestoringPointersOnceItsBufferIsGone() {
        vaos.setConventional(VAOManager.CONV_POSITION, 3, GL11.GL_FLOAT, false, 12, 0L, 6);
        vaos.saveClientArrays(0, GL11.GL_CLIENT_VERTEX_ARRAY_BIT, 4, 6);
        final VAOManager.ClientArrays saved = vaos.savedClientArrays(0);
        assertTrue(vaos.isSavedVboLive(saved));

        vaos.onBindVertexArrayPre(VAO + 1);
        vaos.onDeleteBuffer(6);
        assertFalse(vaos.isSavedVboLive(saved), "a snapshot whose buffer was deleted must not restore its pointers");
    }

    @Test
    void illegalConventionalAndGenericCombinationsAreRejected() {
        assertTrue(VAOManager.isLegalConventional(VAOManager.CONV_POSITION, 3, GL11.GL_FLOAT));
        assertFalse(VAOManager.isLegalConventional(VAOManager.CONV_POSITION, 1, GL11.GL_FLOAT), "positions need at least two components");
        assertFalse(VAOManager.isLegalConventional(VAOManager.CONV_POSITION, 3, GL11.GL_UNSIGNED_BYTE), "positions reject byte components");
        assertFalse(VAOManager.isLegalConventional(VAOManager.CONV_NORMAL, 4, GL11.GL_FLOAT), "normals are exactly three components");
        assertTrue(VAOManager.isLegalConventional(VAOManager.CONV_COLOR, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE), "colors accept the packed BGRA component count");
        assertFalse(VAOManager.isLegalConventional(VAOManager.CONV_POSITION, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE), "only colors accept BGRA");

        assertTrue(VAOManager.isLegalGeneric(7, 4, GL11.GL_FLOAT, false));
        assertTrue(VAOManager.isLegalGeneric(7, 4, GL11.GL_UNSIGNED_BYTE, true), "integer attributes accept byte components");
        assertFalse(VAOManager.isLegalGeneric(7, 4, GL11.GL_FLOAT, true), "integer attributes reject float components");
        assertFalse(VAOManager.isLegalGeneric(VAOManager.MAX_ATTRIBS, 4, GL11.GL_FLOAT, false));
    }

    @Test
    void divisorIsRecordedOnTheEffectiveGenericSlot() {
        assertTrue(vaos.setDivisor(POSITION_LOCATION, 3));
        assertEquals(3, vaos.attrib(POSITION_LOCATION).divisor);
    }

    private int query(int pname, int clientActiveTextureUnit) {
        final int key = VAOManager.clientArrayField(pname, clientActiveTextureUnit);
        assertTrue(key >= 0, () -> "pname 0x" + Integer.toHexString(pname) + " is not a client array query");
        return vaos.clientArrayParam(key);
    }
}
