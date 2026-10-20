package net.coderbot.iris;

import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.ShaderPackFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the read-ahead slot: a pack read ahead of time is taken back by the load it was read for, and
 * refused whenever the selection it was read for no longer applies.
 *
 * <p>The slot is driven through the same package-private publication and consumption a real load uses, so
 * no reflection and no stand-in for the cache is involved.</p>
 */
class IrisPreparedPackTest {

    private static final String SETTINGS = "Pack\n\n";
    private static final String OTHER_SETTINGS = "Pack\n\nSHADOWS=off\n";

    @TempDir
    Path tempDir;

    @AfterEach
    void dropReadAhead() {
        Iris.invalidatePreparedPack();
    }

    @Test
    void aPackReadAheadIsTakenBackByTheLoadItWasReadFor() throws IOException {
        final ShaderPack pack = readMinimalPack();

        assertTrue(Iris.publishPreparedPack(prepared(pack, Iris.preparedPackGeneration())));
        assertSame(pack, Iris.takePreparedPack("Pack", SETTINGS));
    }

    @Test
    void aPackIsOnlyTakenOnce() throws IOException {
        final ShaderPack pack = readMinimalPack();

        assertTrue(Iris.publishPreparedPack(prepared(pack, Iris.preparedPackGeneration())));
        assertSame(pack, Iris.takePreparedPack("Pack", SETTINGS));
        assertNull(Iris.takePreparedPack("Pack", SETTINGS), "a taken pack must not be handed out twice");
    }

    @Test
    void anExplicitInvalidationDropsThePackEvenWhenItsFingerprintStillMatches() throws IOException {
        final ShaderPack pack = readMinimalPack();
        final long readGeneration = Iris.preparedPackGeneration();

        assertTrue(Iris.publishPreparedPack(prepared(pack, readGeneration)));
        assertSame(pack, Iris.takePreparedPack("Pack", SETTINGS));

        // The same pack file, untouched: the fingerprint is identical, so only the invalidation can refuse
        assertTrue(Iris.publishPreparedPack(prepared(pack, Iris.preparedPackGeneration())));
        Iris.invalidatePreparedPack();
        assertNotEquals(readGeneration, Iris.preparedPackGeneration(), "an invalidation advances the generation");
        assertNull(Iris.takePreparedPack("Pack", SETTINGS),
            "the user asked for the pack as it is on disk now, so the read-ahead may not be reused");
    }

    @Test
    void aReadThatFinishesAfterAnInvalidationIsNotPublished() throws IOException {
        final ShaderPack pack = readMinimalPack();
        final long staleGeneration = Iris.preparedPackGeneration();

        Iris.invalidatePreparedPack();

        assertFalse(Iris.publishPreparedPack(prepared(pack, staleGeneration)),
            "a read started for an earlier selection must not replace the one for the current selection");
        assertNull(Iris.takePreparedPack("Pack", SETTINGS));
    }

    @Test
    void aPackReadForAnotherConfigurationIsNotTaken() throws IOException {
        final ShaderPack pack = readMinimalPack();

        assertTrue(Iris.publishPreparedPack(prepared(pack, Iris.preparedPackGeneration())));
        assertNull(Iris.takePreparedPack("Pack", OTHER_SETTINGS),
            "the pack read ahead was parsed with other options, so it is a different pack");
    }

    @Test
    void namesTheFirstLineTwoFingerprintsDifferIn() {
        assertTrue(Iris.firstDifferingLine("Pack\nAAA\n", "Pack\nBBB\n").startsWith("line 2:"),
            "a fingerprint lists the pack, its files and then its options, so the cause is named by line");
        assertTrue(Iris.firstDifferingLine("Pack\nAAA\n", "Pack\nAAA\n").contains("different lengths"));
        assertTrue(Iris.firstDifferingLine(null, OTHER_SETTINGS).contains("nothing was read ahead"));
        assertTrue(Iris.firstDifferingLine(SETTINGS, null).contains("could not be described"));
    }

    private ShaderPack readMinimalPack() throws IOException {
        return ShaderPackFixtures.readMinimal(Files.createDirectories(tempDir.resolve("Pack/shaders")));
    }

    private static Iris.PreparedPack prepared(ShaderPack pack, long generation) {
        return new Iris.PreparedPack(SETTINGS, pack, generation);
    }
}
