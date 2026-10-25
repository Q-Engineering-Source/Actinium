package com.dhj.actinium.compat.fluxloading;

import com.gtnewhorizon.gtnhlib.compat.Mods;

/**
 * Compatibility for FluxLoading (mod id {@code fluxloading}); fixes #102.
 *
 * <p>FluxLoading drives its loading-screen state machine from chunk-compile notifications. Its
 * Celeritas hook only loads when the {@code celeritas} mod id is present, which Actinium does not
 * provide, and the vanilla {@code ChunkRenderWorker.processTask} hook never fires because
 * Actinium meshes chunks on its own builder threads; the state machine stalls in
 * {@code DEFAULT_WORLD_LOADING} forever. This compat forwards the same per-frame signal
 * FluxLoading's own Celeritas mixin would emit. FluxLoading class references live in
 * {@link FluxLoadingNotifyForwarder} so they are never class-loaded unless FluxLoading is
 * installed.</p>
 */
public final class FluxLoadingCompat {
    public static final boolean IS_LOADED = Mods.FLUXLOADING;

    private FluxLoadingCompat() {
    }

    /**
     * Forwards FluxLoading's Celeritas chunk-compile signal once per {@code updateChunks} frame.
     * FluxLoading ignores the signal whenever its state machine is not in a phase that consumes
     * it, so forwarding unconditionally while installed matches the mixin semantics.
     */
    public static void onRenderSectionManagerUpdateChunks() {
        if (IS_LOADED) {
            FluxLoadingNotifyForwarder.notifyChunkCompileTaskProcessed();
        }
    }
}
