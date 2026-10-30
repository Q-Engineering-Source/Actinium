package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatProgramUniformStatesTest {

    @Test
    void relinkReplacesLocationsAndFailedRelinkLeavesNoState() {
        CompatProgramUniformStates states = new CompatProgramUniformStates();
        int[] firstLocations = {4, -1};
        int[] relinkedLocations = {-1, 9};

        long firstLink = states.invalidate(17);
        assertTrue(states.storeLinkedProgram(17, firstLink, firstLocations));
        CompatProgramUniformState firstState = states.get(17);
        assertTrue(states.contains(17));
        assertArrayEquals(firstLocations, firstState.getLocations());
        firstState.markModelViewUploaded(12);

        long relink = states.invalidate(17);
        assertFalse(states.contains(17));
        assertNull(states.get(17));
        assertFalse(firstState.isValid());

        assertTrue(states.storeLinkedProgram(17, relink, relinkedLocations));
        CompatProgramUniformState relinkedState = states.get(17);
        assertTrue(states.contains(17));
        assertNotSame(firstState, relinkedState);
        assertArrayEquals(relinkedLocations, relinkedState.getLocations());
        assertTrue(relinkedState.needsModelViewUpload(12));
    }

    @Test
    void deleteInvalidationAllowsProgramIdentifierReuse() {
        CompatProgramUniformStates states = new CompatProgramUniformStates();
        long firstLink = states.invalidate(31);
        assertTrue(states.storeLinkedProgram(31, firstLink, new int[] {2}));
        states.get(31).markColorUploaded(7);

        long reusedLink = states.invalidate(31);
        assertFalse(states.contains(31));

        int[] reusedLocations = {6};
        assertTrue(states.storeLinkedProgram(31, reusedLink, reusedLocations));
        assertTrue(states.contains(31));
        assertArrayEquals(reusedLocations, states.get(31).getLocations());
        assertTrue(states.get(31).needsColorUpload(7));
    }

    @Test
    void ignoresSuccessfullyLinkedProgramsWithoutCompatUniforms() {
        CompatProgramUniformStates states = new CompatProgramUniformStates();

        long link = states.invalidate(44);
        assertFalse(states.storeLinkedProgram(44, link, new int[] {-1, -1}));

        assertFalse(states.contains(44));
        assertNull(states.get(44));
    }

    @Test
    void rejectsLocationsFromALinkInvalidatedByDelete() {
        CompatProgramUniformStates states = new CompatProgramUniformStates();
        long staleLink = states.invalidate(52);

        states.invalidate(52);

        assertFalse(states.storeLinkedProgram(52, staleLink, new int[] {3}));
        assertFalse(states.contains(52));
    }

    @Test
    void rejectsLocationsFromALinkSupersededByAnotherLink() {
        CompatProgramUniformStates states = new CompatProgramUniformStates();
        long staleLink = states.invalidate(63);
        long currentLink = states.invalidate(63);

        assertFalse(states.storeLinkedProgram(63, staleLink, new int[] {3}));
        assertTrue(states.storeLinkedProgram(63, currentLink, new int[] {8}));
        assertArrayEquals(new int[] {8}, states.get(63).getLocations());
    }
}
