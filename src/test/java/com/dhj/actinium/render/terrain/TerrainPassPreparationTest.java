package com.dhj.actinium.render.terrain;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks when a frame's terrain passes are prepared for compute culling.
 *
 * <p>The terrain setup of a frame only marks the preparation, because the shadow pass draws after it and walks its own
 * render lists; preparing during the setup would produce command ranges the shadow walk discards. The first main
 * terrain draw of the frame therefore prepares once, and a preparation that throws has to stay marked so the next draw
 * retries instead of drawing ranges that were never culled.
 */
class TerrainPassPreparationTest {
    private final TerrainPassPreparation preparation = new TerrainPassPreparation();
    private final AtomicInteger preparedPasses = new AtomicInteger();

    @Test
    void preparesOnTheFirstTerrainDrawOfTheSetup() {
        this.preparation.markPending();

        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        assertEquals(1, this.preparedPasses.get());
    }

    @Test
    void reusesThePreparationForTheLaterDrawsOfTheSameSetup() {
        this.preparation.markPending();

        this.preparation.prepareIfPending(false, this::prepareEveryPass);
        this.preparation.prepareIfPending(false, this::prepareEveryPass);
        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        assertEquals(1, this.preparedPasses.get(),
            "a later draw that prepares again discards the ranges the frame already culled");
    }

    @Test
    void preparesAgainAfterTheNextTerrainSetup() {
        this.preparation.markPending();
        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        this.preparation.markPending();
        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        assertEquals(2, this.preparedPasses.get());
    }

    @Test
    void keepsThePreparationForTheMainDrawOfTheFrameWhenAShadowDrawComesFirst() {
        this.preparation.markPending();

        this.preparation.prepareIfPending(true, this::prepareEveryPass);

        assertEquals(0, this.preparedPasses.get(),
            "the shadow pass draws its own render lists, so its preparation would be discarded by the main draw");

        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        assertEquals(1, this.preparedPasses.get(),
            "the main draw after the shadow pass prepares the lists it draws");
    }

    @Test
    void retriesThePreparationAfterAFailure() {
        this.preparation.markPending();
        IllegalStateException failure = new IllegalStateException("compute dispatch failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> this.preparation.prepareIfPending(false, () -> {
                throw failure;
            }));
        assertEquals(failure, thrown);

        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        assertEquals(1, this.preparedPasses.get(),
            "a failed preparation must stay pending, otherwise the frame draws ranges that were never culled");
    }

    @Test
    void doesNothingWithoutAPendingSetup() {
        this.preparation.prepareIfPending(false, this::prepareEveryPass);

        assertEquals(0, this.preparedPasses.get());
    }

    private void prepareEveryPass() {
        this.preparedPasses.incrementAndGet();
    }
}
