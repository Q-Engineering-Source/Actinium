package com.gtnewhorizons.angelica.iris;

import net.coderbot.iris.Iris;
import net.coderbot.iris.shaderpack.ProgramSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the selected shader pack while the player sits at the main menu, so the world they open next does
 * not pay for parsing it on its first frame.
 *
 * <p>The main menu is the first place the game sits still for long enough to be worth the work, so {@link
 * #start()} is called from {@code GuiMainMenu.initGui}. Everything that touches OpenGL - the environment
 * defines the pack is read with, and the transformer state every transform depends on - happens on the
 * calling thread, before the read is handed to a background thread: the read itself only touches files,
 * and the transforms of what it read run on the shader transform pool.</p>
 *
 * <p>Runs once per pack selection. {@link Iris#invalidatePreparedPack()} lets it run again, which is how a
 * selection applied after the first title screen is read ahead too.</p>
 */
public final class ShaderWarmup {
    private static final Logger LOGGER = LogManager.getLogger("Actinium/ShaderWarmup");
    private static final String THREAD_NAME = "Actinium-Shader-Warmup";

    /**
     * What the read-ahead asks of the rest of the client, as one replaceable set of steps.
     *
     * <p>Reading a pack needs a running game - a GL context for the environment defines, a render system
     * that has initialized, a config with a selection in it - so the decision about when to read is only
     * testable if the steps it decides between are separable from that game. The production
     * implementation is {@link #GAME}; the tests drive the decision with their own.</p>
     */
    interface Client {
        /** {@return whether shaders are enabled at all}, the outermost reason not to read anything */
        boolean shadersEnabled();

        /**
         * {@return a reader for the pack the config has selected, or null when there is nothing to read}
         *
         * <p>Called on the main thread. Null is the normal answer for most of the session: no pack is
         * selected, the render system has not initialized yet, or a pack is already loaded.</p>
         */
        @Nullable
        Iris.SelectedPackReader prepareSelectedPack();

        /** {@return whether this caller owns the read-ahead for the current selection} */
        boolean claimSelection();

        /** Initializes the transformer state the read-ahead's transforms will cache against. Main thread. */
        void prepareTransforms();

        /** Hands the program set the read produced to the transform pool, so the pipeline finds it cached. */
        void warmUpTransforms(String packName, ProgramSet programs);

        /** Runs the read on a background thread, returning immediately. */
        void runInBackground(Runnable task);
    }

    /** The client this runs against in the game. */
    private static final Client GAME = new Client() {
        @Override
        public boolean shadersEnabled() {
            return Iris.enabled;
        }

        @Override
        @Nullable
        public Iris.SelectedPackReader prepareSelectedPack() {
            return Iris.prepareSelectedShaderPack();
        }

        @Override
        public boolean claimSelection() {
            return Iris.claimShaderWarmup();
        }

        @Override
        public void prepareTransforms() {
            Iris.prepareShaderWarmup();
        }

        @Override
        public void warmUpTransforms(String packName, ProgramSet programs) {
            Iris.warmupPreparedTransforms(packName, programs);
        }

        @Override
        public void runInBackground(Runnable task) {
            startThread(task);
        }
    };

    private ShaderWarmup() {}

    /**
     * Reads the selected pack ahead of time, unless it is not worth it or has already been done.
     *
     * <p>Main thread only, and it never blocks: the read is started on its own thread. Called from the main
     * menu's {@code initGui}, so it runs again every time the player comes back to the title screen; the
     * claim is what makes only the first of those do anything.</p>
     *
     * <p>The hook that calls this also checks that shaders are enabled, which is most of the session for
     * anyone who does not use them. The check is repeated here because whether the read-ahead is worth
     * starting is this entry point's own decision: a caller does not have to know it, and this is where it
     * is exercised without a running game.</p>
     */
    public static void start() {
        start(GAME);
    }

    /**
     * The read-ahead, against a given set of client steps.
     *
     * <p>A failure here is reported and the read-ahead abandoned for this selection: this is called from
     * {@code initGui}, where an exception would take the menu down for an optimization that is only worth
     * something when it works.</p>
     */
    static void start(Client client) {
        if (!client.shadersEnabled()) {
            LOGGER.debug("Shader warmup: shaders are disabled, so there is nothing to read ahead");
            return;
        }

        try {
            final Iris.SelectedPackReader selected = client.prepareSelectedPack();
            if (selected == null) {
                LOGGER.debug("Shader warmup: no shaderpack to read ahead for the current selection");
                return;
            }

            if (!client.claimSelection()) {
                LOGGER.debug("Shader warmup: the shaderpack \"{}\" was already read ahead for this selection", selected.name());
                return;
            }

            // Before the read is handed off, not on the thread that reads: the transformer state this
            // initializes is derived from the GL capabilities the render system cached at startup, and the
            // transforms the read-ahead submits cache their results without keying on it.
            client.prepareTransforms();

            LOGGER.info("Shader warmup: reading the shaderpack \"{}\" ahead of time", selected.name());
            client.runInBackground(() -> warmUp(selected, client));
        } catch (Throwable failure) {
            LOGGER.warn("Shader warmup: could not start reading the selected shaderpack ahead of time,"
                + " the pack will be read when the world loads", failure);
        }
    }

    /**
     * Reads a pack and starts transforming what it read, on the read-ahead thread.
     *
     * <p>A pack that cannot be read is not an error anyone needs to see twice: the load that follows reads
     * it the normal way and reports whatever is wrong with it in the place the player expects. The failure
     * is still logged here, because a read-ahead that never produces anything is otherwise invisible.</p>
     */
    private static void warmUp(Iris.SelectedPackReader selected, Client client) {
        final long startNanos = System.nanoTime();
        try {
            final ProgramSet programs = selected.read().get();
            if (programs == null) {
                LOGGER.info("Shader warmup: nothing of the shaderpack \"{}\" could be read ahead, done in {} ms",
                    selected.name(), elapsedMillis(startNanos));
                return;
            }

            client.warmUpTransforms(selected.name(), programs);
            LOGGER.info("Shader warmup: read the shaderpack \"{}\" ahead and submitted its transform warmup in {} ms",
                selected.name(), elapsedMillis(startNanos));
        } catch (Throwable failure) {
            LOGGER.warn("Shader warmup: reading the shaderpack \"" + selected.name() + "\" ahead failed after "
                + elapsedMillis(startNanos) + " ms, the pack will be read when the world loads", failure);
        }
    }

    /**
     * Starts the read-ahead thread.
     *
     * <p>Not the shared {@code AngelicaWorkers} pool, for a reason that is about the read rather than about
     * it being long: that pool is what the include graph and the pack fingerprint parallelize their file
     * reads on, and they deliberately run those reads inline when the caller is already one of the pool's
     * threads. Reading a pack on the pool would therefore serialize exactly the reads it spreads out, and
     * would put a multi-second parse in the way of the work the pool exists for. From a thread of its own
     * this call is a caller like any other, and the pool is used the way it was designed to be.</p>
     *
     * <p>One thread at a time: {@link #start} only reaches this after it claimed the current selection.</p>
     */
    private static void startThread(Runnable task) {
        final Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static String elapsedMillis(long startNanos) {
        return String.format("%.1f", (System.nanoTime() - startNanos) / 1_000_000.0);
    }
}
