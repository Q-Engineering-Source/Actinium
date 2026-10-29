package com.gtnewhorizons.angelica.iris;

import net.coderbot.iris.Iris;
import net.coderbot.iris.shaderpack.DimensionId;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.ShaderPackFixtures;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers when the main menu reads the selected shader pack ahead of time, and what happens when that read
 * fails.
 *
 * <p>The read-ahead is driven through its {@link ShaderWarmup.Client} steps, which is how it reaches the
 * game: no GL context, no render system and no {@code Minecraft} instance is involved, and the background
 * step is the test's to run. What is asserted is where the decision is made - which is the whole of what
 * the main menu hook contributes.</p>
 */
class ShaderWarmupTest {

    private static final String PACK_NAME = "Pack";

    @TempDir
    Path tempDir;

    @Test
    void doesNotReadAnythingWhenShadersAreDisabled() {
        final RecordingClient client = new RecordingClient();
        client.shadersEnabled = false;

        ShaderWarmup.start(client);

        assertEquals(List.of(), client.events, "shaders are off, so nothing is read and no claim is taken");
    }

    @Test
    void doesNotReadAnythingWhenNoPackIsSelected() {
        final RecordingClient client = new RecordingClient();
        client.hasSelection = false;

        ShaderWarmup.start(client);

        assertEquals(List.of("prepare"), client.events,
            "there is no pack to read, so the read-ahead leaves the selection unclaimed for a later menu");
    }

    @Test
    void doesNotReadAnythingWhenTheSelectionWasAlreadyRead() {
        final RecordingClient client = new RecordingClient();
        client.claimed = false;

        ShaderWarmup.start(client);

        assertEquals(List.of("prepare", "claim"), client.events,
            "a selection is read once; the menu is rebuilt on every return to the title screen");
    }

    @Test
    void readsThePackAheadAndWarmsItsTransforms() throws IOException {
        final RecordingClient client = new RecordingClient();
        client.programs = programs();

        ShaderWarmup.start(client);
        client.awaitBackground();

        assertEquals(List.of("prepare", "claim", "prepareTransforms", "background", "read", "warmup"), client.events);
        assertSame(client.programs, client.warmedPrograms, "the program set the read produced is the one that is warmed");
        assertEquals(PACK_NAME, client.warmedPackName);
    }

    @Test
    void initializesTheTransformerBeforeTheReadStarts() throws IOException {
        final RecordingClient client = new RecordingClient();
        client.programs = programs();

        ShaderWarmup.start(client);
        client.awaitBackground();

        assertTrue(client.events.indexOf("prepareTransforms") < client.events.indexOf("read"),
            "the transformer state every transform caches against is initialized on the main thread, before the read");
        assertTrue(client.events.indexOf("prepareTransforms") < client.events.indexOf("background"));
    }

    @Test
    void returnsBeforeTheReadFinishes() throws IOException {
        final RecordingClient client = new RecordingClient();
        client.programs = programs();
        final CountDownLatch releaseRead = new CountDownLatch(1);
        client.readGate = releaseRead;

        ShaderWarmup.start(client);

        assertNull(client.warmedPrograms, "the main menu must not wait for the pack to be read");
        assertTrue(client.backgroundStarted, "the read runs on a thread of its own");
        releaseRead.countDown();
        client.awaitBackground();
        assertTrue(client.events.contains("warmup"), "the read that was released is the one that warms the transforms");
    }

    @Test
    void aReadThatFailsIsReportedAndDoesNotEscape() {
        final RecordingClient client = new RecordingClient();
        client.failRead = true;

        ShaderWarmup.start(client);
        client.awaitBackground();

        assertEquals(0, client.backgroundFinished.getCount(), "the failed read must not leave the warmup hanging");
        assertNull(client.warmedPrograms, "a read that produced nothing has nothing to warm");
        assertTrue(client.events.contains("read"), "the read was attempted: this is a failed read, not a skipped one");
    }

    @Test
    void aFailureWhileStartingDoesNotEscapeTheMenu() {
        final RecordingClient client = new RecordingClient();
        client.failPreparation = true;

        ShaderWarmup.start(client);

        assertEquals(List.of("prepare"), client.events, "a pack that cannot be prepared is not read");
        assertFalse(client.backgroundStarted, "no thread is started for a read-ahead that could not be prepared");
    }

    private ProgramSet programs() throws IOException {
        final ShaderPack pack = ShaderPackFixtures.readMinimal(Files.createDirectories(tempDir.resolve("Pack/shaders")));
        return pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId());
    }

    /** The client steps the read-ahead performs, recording what it asked for and playing back a selection. */
    private static final class RecordingClient implements ShaderWarmup.Client {
        final List<String> events = new ArrayList<>();
        final CountDownLatch backgroundFinished = new CountDownLatch(1);
        boolean shadersEnabled = true;
        boolean claimed = true;
        boolean hasSelection = true;
        boolean failPreparation;
        boolean failRead;
        boolean backgroundStarted;
        @Nullable
        CountDownLatch readGate;
        @Nullable
        ProgramSet programs;
        @Nullable
        ProgramSet warmedPrograms;
        @Nullable
        String warmedPackName;

        @Override
        public boolean shadersEnabled() {
            return shadersEnabled;
        }

        @Override
        @Nullable
        public Iris.SelectedPackReader prepareSelectedPack() {
            events.add("prepare");
            if (failPreparation) {
                throw new IllegalStateException("the selection could not be described");
            }
            return hasSelection ? new Iris.SelectedPackReader(PACK_NAME, this::read) : null;
        }

        @Override
        public boolean claimSelection() {
            events.add("claim");
            return claimed;
        }

        @Override
        public void prepareTransforms() {
            events.add("prepareTransforms");
        }

        @Override
        public void warmUpTransforms(String packName, ProgramSet programs) {
            events.add("warmup");
            warmedPackName = packName;
            warmedPrograms = programs;
        }

        @Override
        public void runInBackground(Runnable task) {
            events.add("background");
            backgroundStarted = true;
            final Thread thread = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    backgroundFinished.countDown();
                }
            }, "shader-warmup-test");
            thread.setDaemon(true);
            thread.start();
        }

        void awaitBackground() {
            try {
                assertTrue(backgroundFinished.await(10, TimeUnit.SECONDS), "the read-ahead must finish");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the read-ahead", e);
            }
        }

        private ProgramSet read() {
            events.add("read");
            final CountDownLatch gate = readGate;
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while blocking the test read", e);
                }
            }
            if (failRead) {
                throw new IllegalStateException("corrupted pack");
            }
            if (programs == null) {
                throw new IllegalStateException("this test did not provide a program set");
            }
            return programs;
        }
    }
}
