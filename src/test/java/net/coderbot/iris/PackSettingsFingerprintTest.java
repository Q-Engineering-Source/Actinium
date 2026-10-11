package net.coderbot.iris;

import net.coderbot.iris.shaderpack.StringPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pack fingerprint decides whether a read-ahead pack and the saved programs below it may be reused,
 * so it has to follow everything the loaded pack depends on and nothing else.
 */
class PackSettingsFingerprintTest {

    @TempDir
    Path dir;

    private static final List<StringPair> DEFINES = List.of(new StringPair("MC_VERSION", "11202"));

    private static String describe(Path packRoot, Properties options, boolean byContent) {
        return Iris.describeSettings("pack", packRoot, options, DEFINES, byContent);
    }

    @Test
    void folderFingerprintFollowsNamesSizesAndTimestamps() throws IOException {
        final Path pack = Files.createDirectories(dir.resolve("pack"));
        final Path nested = Files.createDirectories(pack.resolve("world0"));
        final Path top = Files.write(pack.resolve("gbuffers_basic.fsh"), new byte[] {1, 2, 3});
        Files.write(nested.resolve("shadow.fsh"), new byte[] {4});

        final Properties options = new Properties();
        final String first = describe(pack, options, false);
        assertEquals(first, describe(pack, options, false), "nothing changed, so the fingerprint is unchanged");

        Files.setLastModifiedTime(top, FileTime.fromMillis(1_000_000_000_000L));
        assertNotEquals(first, describe(pack, options, false), "a touched file has to be read again");

        final String afterTouch = describe(pack, options, false);
        Files.write(pack.resolve("new.fsh"), new byte[] {5});
        assertNotEquals(afterTouch, describe(pack, options, false), "a new file in the pack is part of it");

        final String withNewFile = describe(pack, options, false);
        Files.write(nested.resolve("new.fsh"), new byte[] {6});
        assertNotEquals(withNewFile, describe(pack, options, false), "so is one in a dimension folder");
    }

    @Test
    void contentFingerprintIgnoresTimestampsAndFollowsContent() throws IOException {
        final Path pack = Files.createDirectories(dir.resolve("pack"));
        final Path shader = Files.write(pack.resolve("composite.fsh"), new byte[] {1, 2, 3});

        final Properties options = new Properties();
        final String first = describe(pack, options, true);
        Files.setLastModifiedTime(shader, FileTime.fromMillis(1_000_000_000_000L));
        assertEquals(first, describe(pack, options, true), "the same content is the same compiled program");

        Files.write(shader, new byte[] {1, 2, 4});
        assertNotEquals(first, describe(pack, options, true), "changed content has to be compiled again");
    }

    @Test
    void optionsDefinesAndPackKindArePartOfTheFingerprint() throws IOException {
        final Path pack = Files.createDirectories(dir.resolve("pack"));
        Files.write(pack.resolve("gbuffers_basic.fsh"), new byte[] {1});

        final Properties options = new Properties();
        final String plain = describe(pack, options, true);

        final Properties changed = new Properties();
        changed.setProperty("SHADOW_QUALITY", "high");
        assertNotEquals(plain, describe(pack, changed, true), "shader options change the compiled programs");

        assertEquals(plain, describe(pack, new Properties(), true), "the same options give the same fingerprint");

        final String otherPack = Iris.describeSettings("other", pack, new Properties(), DEFINES, true);
        assertNotEquals(plain, otherPack, "the pack name is part of it");

        final String otherDefine = Iris.describeSettings("pack", pack, new Properties(),
            List.of(new StringPair("MC_VERSION", "11202"), new StringPair("IRIS_FEATURE_XX", "true")), true);
        assertNotEquals(plain, otherDefine, "environment defines reach the shaders");
    }

    @Test
    void zipPacksUseSizeAndTimestampAndMissingOnesHaveNoFingerprint() throws IOException {
        final Path zip = Files.write(dir.resolve("pack.zip"), new byte[] {1, 2, 3, 4});
        final String first = describe(zip, new Properties(), true);
        assertEquals(first, describe(zip, new Properties(), false),
            "a single pack file is described by its own state, whichever kind of description was asked for");

        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_000_000_000_000L));
        assertNotEquals(first, describe(zip, new Properties(), true));

        assertNull(describe(dir.resolve("missing"), new Properties(), true));
    }
}
