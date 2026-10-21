package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListNamespaceTest {

    @Test
    void independentShareGroupsCanCompileAndDeleteTheSameListId() {
        final DisplayListNamespaces namespaces = new DisplayListNamespaces();
        final DisplayListNamespace first = namespaces.forShareGroup(1L);
        final DisplayListNamespace second = namespaces.forShareGroup(2L);
        final int firstId = first.allocateRange(1);
        final int secondId = second.allocateRange(1);

        assertEquals(firstId, secondId);
        assertFalse(first.contains(firstId), "glGenLists reserves a name before it becomes a display list");
        first.put(firstId, CompiledDisplayList.EMPTY);
        assertTrue(first.contains(firstId));
        assertFalse(second.contains(secondId), "another group's list is not visible before it compiles its own id");
        assertNull(second.get(secondId));

        second.put(secondId, CompiledDisplayList.EMPTY);
        assertTrue(second.contains(secondId));
        assertNotSame(first, second);
        assertSame(CompiledDisplayList.EMPTY, first.get(firstId));
        assertSame(CompiledDisplayList.EMPTY, second.get(secondId));

        assertEquals(List.of(CompiledDisplayList.EMPTY), first.deleteRange(firstId, 1));
        assertFalse(first.contains(firstId));
        assertTrue(second.contains(secondId), "deleting in one share group leaves the other group intact");
        assertEquals(firstId, first.allocateRange(1), "deleting a name makes it reusable in its own group");
    }

    @Test
    void compiledNamesWithoutAnAllocationAreSkippedByLaterRanges() {
        final DisplayListNamespace namespace = new DisplayListNamespace(3L);
        namespace.put(1, CompiledDisplayList.EMPTY);

        assertEquals(2, namespace.allocateRange(1));
        assertTrue(namespace.contains(1));
        assertFalse(namespace.contains(2));
    }

    @Test
    void multiNameRangeSkipsACompiledNameInItsMiddleAndRemainsContiguous() {
        final DisplayListNamespace namespace = new DisplayListNamespace(6L);
        namespace.put(33, CompiledDisplayList.EMPTY);

        final int firstRange = namespace.allocateRange(3);
        final int secondRange = namespace.allocateRange(3);

        assertEquals(64, firstRange,
            "the initial 32..34 candidate overlaps manually compiled ID 33 and must be discarded");
        assertEquals(96, secondRange,
            "the next three-name allocation begins after the first full allocator word");
        assertTrue(namespace.contains(33), "range retry must preserve the manually compiled list");
        assertFalse(namespace.contains(32));
        assertFalse(namespace.contains(34));
        assertTrue(firstRange + 2 < secondRange, "allocated ranges must not overlap");
    }

    @Test
    void contextsInOneShareGroupResolveToOneNamespaceUntilGroupRetirement() {
        final DisplayListNamespaces namespaces = new DisplayListNamespaces();
        final DisplayListNamespace firstContext = namespaces.forShareGroup(4L);
        final DisplayListNamespace sharedContext = namespaces.forShareGroup(4L);
        final DisplayListNamespace independentGroup = namespaces.forShareGroup(5L);
        firstContext.put(7, CompiledDisplayList.EMPTY);
        independentGroup.put(7, CompiledDisplayList.EMPTY);

        assertSame(firstContext, sharedContext);
        assertTrue(sharedContext.contains(7));
        assertEquals(List.of(CompiledDisplayList.EMPTY), namespaces.retireShareGroup(4L));
        assertTrue(independentGroup.contains(7), "retiring one group leaves the other group alive");

        final DisplayListNamespace nextOwner = namespaces.forShareGroup(4L);
        assertNotSame(firstContext, nextOwner);
        assertFalse(nextOwner.contains(7));
    }
}
