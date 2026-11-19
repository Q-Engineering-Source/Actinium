package com.gtnewhorizons.angelica.mixins.interfaces;

/**
 * Per-renderer batching verdict, learnt from observed draws rather than declared up front.
 *
 * <p>Both entity renderers and tile entity special renderers carry one of these. The observation
 * brackets in {@code BatchEligibility} upgrade an {@code UNKNOWN} renderer to {@code SAFE} and
 * demote one that mixes its own draws with batched model parts to {@code DENIED}, so the cost of
 * trying a renderer stays one pass.</p>
 */
public interface IBatchEligibility {

    byte angelica$batchState();

    void angelica$setBatchState(byte state);
}
