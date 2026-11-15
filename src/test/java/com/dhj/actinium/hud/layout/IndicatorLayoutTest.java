package com.dhj.actinium.hud.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.dhj.actinium.hud.layout.IndicatorRowType.CPU;
import static com.dhj.actinium.hud.layout.IndicatorRowType.FPS;
import static com.dhj.actinium.hud.layout.IndicatorRowType.FPS_LOW;
import static com.dhj.actinium.hud.layout.IndicatorRowType.GPU;
import static com.dhj.actinium.hud.layout.IndicatorRowType.MEMORY;
import static com.dhj.actinium.hud.layout.IndicatorRowType.VRAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorLayoutTest {
    private static final List<IndicatorRowType> ALL_ROWS = List.of(FPS, FPS_LOW, MEMORY, CPU, GPU, VRAM);

    @Test
    void emptyConfigKeepsEveryRowAvailable() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of());

        assertTrue(layout.enabledRows().isEmpty());
        assertEquals(ALL_ROWS, layout.availableRows());
        assertTrue(layout.toConfig().isEmpty());
    }

    @Test
    void defaultRowLeavesTheRemainingRowsAvailable() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps"));

        assertEquals(List.of(FPS), layout.enabledRows());
        assertEquals(List.of(FPS_LOW, MEMORY, CPU, GPU, VRAM), layout.availableRows());
    }

    @Test
    void resolvesEveryKnownIdInStoredOrder() {
        List<String> rows = List.of("vram", "fps", "cpu", "memory", "gpu", "fps_low");

        IndicatorLayout layout = IndicatorLayout.fromConfig(rows);

        assertEquals(List.of(VRAM, FPS, CPU, MEMORY, GPU, FPS_LOW), layout.enabledRows());
        assertTrue(layout.availableRows().isEmpty());
        assertEquals(rows, layout.toConfig());
    }

    @Test
    void dropsIdsThatThisBuildDoesNotKnow() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "temperature", "memory"));

        assertEquals(List.of(FPS, MEMORY), layout.enabledRows());
    }

    @Test
    void duplicateIdsKeepTheFirstOccurrence() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("memory", "fps", "memory", "gpu", "fps"));

        assertEquals(List.of(MEMORY, FPS, GPU), layout.enabledRows());
        assertEquals(List.of(FPS_LOW, CPU, VRAM), layout.availableRows());
    }

    @Test
    void movesEnabledRowTowardsTheEndOfTheColumn() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "memory", "cpu"));

        IndicatorLayout moved = layout.insertEnabled(FPS, 2);

        assertEquals(List.of(MEMORY, CPU, FPS), moved.enabledRows());
        assertEquals(List.of(FPS, MEMORY, CPU), layout.enabledRows());
    }

    @Test
    void movesEnabledRowTowardsTheStartOfTheColumn() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "memory", "cpu"));

        assertEquals(List.of(CPU, FPS, MEMORY), layout.insertEnabled(CPU, 0).enabledRows());
    }

    @Test
    void keepsOrderWhenRowIsDroppedOnItsOwnPosition() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "memory"));

        assertEquals(List.of(FPS, MEMORY), layout.insertEnabled(FPS, 0).enabledRows());
        assertEquals(List.of(FPS, MEMORY), layout.insertEnabled(MEMORY, 1).enabledRows());
    }

    @Test
    void insertsAvailableRowAtRequestedPosition() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "memory"));

        IndicatorLayout moved = layout.insertEnabled(VRAM, 1);

        assertEquals(List.of(FPS, VRAM, MEMORY), moved.enabledRows());
        assertEquals(List.of(FPS_LOW, CPU, GPU), moved.availableRows());
    }

    @Test
    void appendsAvailableRowAtTheEndOfTheEnabledColumn() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps"));

        assertEquals(List.of(FPS, MEMORY), layout.insertEnabled(MEMORY, 1).enabledRows());
    }

    @Test
    void rejectsInsertIndexOutsideTheResultingColumn() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "memory"));

        assertThrows(IndexOutOfBoundsException.class, () -> layout.insertEnabled(CPU, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.insertEnabled(CPU, -1));
        // Moving a row within its own column counts positions after the row was lifted out.
        assertThrows(IndexOutOfBoundsException.class, () -> layout.insertEnabled(FPS, 2));
    }

    @Test
    void removesEnabledRowBackIntoTheAvailableColumn() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("memory", "fps"));

        IndicatorLayout removed = layout.removeEnabled(FPS);

        assertEquals(List.of(MEMORY), removed.enabledRows());
        assertEquals(List.of(FPS, FPS_LOW, CPU, GPU, VRAM), removed.availableRows());
        assertEquals(List.of(MEMORY, FPS), layout.enabledRows());
    }

    @Test
    void removedRowCanBeInsertedAgain() {
        IndicatorLayout removed = IndicatorLayout.fromConfig(List.of("fps", "memory")).removeEnabled(FPS);

        assertEquals(List.of(MEMORY, FPS), removed.insertEnabled(FPS, 1).enabledRows());
    }

    @Test
    void removingAvailableRowKeepsTheLayoutUnchanged() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps"));

        assertEquals(layout, layout.removeEnabled(VRAM));
    }

    @Test
    void bothColumnsHoldEveryRowExactlyOnceAfterEachEdit() {
        IndicatorLayout layout = IndicatorLayout.fromConfig(List.of("fps", "gpu"));
        assertPartitioned(layout);

        layout = layout.removeEnabled(FPS);
        assertPartitioned(layout);

        layout = layout.insertEnabled(FPS, 0);
        assertPartitioned(layout);

        layout = layout.insertEnabled(CPU, 1);
        assertPartitioned(layout);

        layout = layout.removeEnabled(GPU);
        assertPartitioned(layout);

        layout = layout.insertEnabled(GPU, layout.enabledRows().size());
        assertPartitioned(layout);

        assertEquals(List.of(FPS, CPU, GPU), layout.enabledRows());
    }

    @Test
    void configRoundTripPreservesTheEnabledOrder() {
        IndicatorLayout stored = IndicatorLayout.fromConfig(List.of("gpu", "fps", "cpu"));
        IndicatorLayout edited = IndicatorLayout.fromConfig(List.of("fps"))
            .insertEnabled(GPU, 0)
            .removeEnabled(FPS);

        assertEquals(stored, IndicatorLayout.fromConfig(stored.toConfig()));
        assertEquals(edited, IndicatorLayout.fromConfig(edited.toConfig()));
    }

    @Test
    void layoutDoesNotShareStateWithTheCaller() {
        List<String> source = new ArrayList<>(List.of("fps", "memory"));
        IndicatorLayout layout = IndicatorLayout.fromConfig(source);

        source.clear();

        assertEquals(List.of(FPS, MEMORY), layout.enabledRows());
        assertThrows(UnsupportedOperationException.class, () -> layout.enabledRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> layout.availableRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> layout.toConfig().clear());
    }

    private static void assertPartitioned(IndicatorLayout layout) {
        List<IndicatorRowType> union = new ArrayList<>(layout.enabledRows());
        union.addAll(layout.availableRows());

        assertEquals(ALL_ROWS.size(), union.size());
        for (IndicatorRowType type : ALL_ROWS) {
            assertEquals(1, Collections.frequency(union, type));
        }
    }
}
