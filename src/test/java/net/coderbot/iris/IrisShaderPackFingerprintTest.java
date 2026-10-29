package net.coderbot.iris;

import net.coderbot.iris.shaderpack.ShaderPackFixtures;
import net.coderbot.iris.shaderpack.StringPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fingerprint a pack read ahead of time is validated against: what it describes, and what it
 * deliberately cannot see - which is why the read-ahead generation exists.
 */
class IrisShaderPackFingerprintTest {

    private static final List<StringPair> ENVIRONMENT = ShaderPackFixtures.environment();

    @TempDir
    Path tempDir;

    @Test
    void describesThePackNameItsFilesItsOptionsAndTheEnvironment() throws IOException {
        final Path pack = Files.createDirectories(tempDir.resolve("Pack"));
        ShaderPackFixtures.writeProgram(pack, "gbuffers_basic", "base");

        final String fingerprint = Iris.describeSettings("Pack", pack, new Properties(), ENVIRONMENT, false);
        assertNotNull(fingerprint);
        assertEquals(fingerprint, Iris.describeSettings("Pack", pack, new Properties(), ENVIRONMENT, false),
            "nothing changed, so nothing may differ");

        assertNotEquals(fingerprint, Iris.describeSettings("Other", pack, new Properties(), ENVIRONMENT, false),
            "another pack name selects another pack");
        assertNotEquals(fingerprint, Iris.describeSettings("Pack", pack, options("SHADOWS", "off"), ENVIRONMENT, false),
            "an applied option changes what the pack resolves to");
        assertNotEquals(fingerprint, Iris.describeSettings("Pack", pack, new Properties(),
                List.of(new StringPair("MC_VERSION", "11201")), false),
            "the environment the pack is parsed under changes what it resolves to");
    }

    @Test
    void describesTheFilesOfAFolderRecursively() throws IOException {
        final Path pack = Files.createDirectories(tempDir.resolve("Pack"));
        ShaderPackFixtures.writeProgram(pack, "gbuffers_basic", "base");
        ShaderPackFixtures.writeProgram(pack.resolve("world-1"), "gbuffers_basic", "nether");

        final String fingerprint = Iris.describeSettings("Pack", pack, new Properties(), ENVIRONMENT, false);
        assertNotNull(fingerprint);
        // Every file is listed under its pack-relative path, in a stable order
        assertTrue(fingerprint.contains("world-1/gbuffers_basic.vsh|"),
            "a dimension folder is part of what the pack resolves to");

        ShaderPackFixtures.writeProgram(pack.resolve("world-1"), "gbuffers_basic", "nether2");
        assertNotEquals(fingerprint, Iris.describeSettings("Pack", pack, new Properties(), ENVIRONMENT, false),
            "a changed file changes the fingerprint");
    }

    @Test
    void aFileEditedWithoutChangingItsSizeOrTimestampKeepsTheSameStatFingerprint() throws IOException {
        final Path pack = Files.createDirectories(tempDir.resolve("Pack"));
        final Path program = pack.resolve("gbuffers_basic.vsh");
        final Path fragment = pack.resolve("gbuffers_basic.fsh");
        Files.writeString(program, "AAAA", StandardCharsets.UTF_8);
        Files.writeString(fragment, "CCCC", StandardCharsets.UTF_8);
        final FileTime stamp = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(program, stamp);
        Files.setLastModifiedTime(fragment, stamp);

        final String read = Iris.describeSettings("Pack", pack, new Properties(), ENVIRONMENT, false);

        // Same byte count, restored timestamp: an editor that preserves metadata looks unchanged here
        Files.writeString(program, "BBBB", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(program, stamp);

        final String selected = Iris.describeSettings("Pack", pack, new Properties(), ENVIRONMENT, false);
        assertEquals(read, selected,
            "size and timestamp are all the cheap fingerprint sees, so it cannot tell this edit from a touch");
    }

    @Test
    void describesAZipPackByTheFileItself() throws IOException {
        final Path zip = tempDir.resolve("Pack.zip");
        Files.writeString(zip, "not really a zip, but the fingerprint only stats the file", StandardCharsets.UTF_8);
        final FileTime stamp = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(zip, stamp);

        final String fingerprint = Iris.describeSettings("Pack.zip", zip, new Properties(), ENVIRONMENT, false);
        assertNotNull(fingerprint);
        assertEquals(fingerprint, Iris.describeSettings("Pack.zip", zip, new Properties(), ENVIRONMENT, false));

        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_700_000_001_000L));
        assertNotEquals(fingerprint, Iris.describeSettings("Pack.zip", zip, new Properties(), ENVIRONMENT, false),
            "a zip pack is one file, so its timestamp is the whole fingerprint of its contents");
    }

    @Test
    void aPackThatIsNotThereCannotBeDescribed() {
        assertNull(Iris.describeSettings("Missing", tempDir.resolve("Missing"), new Properties(), ENVIRONMENT, false));
    }

    private static Properties options(String key, String value) {
        final Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
