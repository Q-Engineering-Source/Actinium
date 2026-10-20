package net.coderbot.iris.gui.screen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that the English language file defines every key the saved-program controls use.
 *
 * <p>A missing key is not a crash: {@code I18n} falls back to the key itself, so the screen would render
 * {@code options.iris.cachedPrograms.delete} as the button label. Only the other languages fall back to
 * English, which is why this checks the English file and nothing else.</p>
 */
class ShaderPackCacheLangKeysTest {

    /** Every key the saved-program controls translate. */
    private static final List<String> KEYS = List.of(
        "options.iris.saveCompiledShaders.on",
        "options.iris.saveCompiledShaders.off",
        "options.iris.saveCompiledShaders.tooltip",
        "options.iris.saveCompiledShaders.unsupported",
        "options.iris.saveCompiledShaders.reload",
        "options.iris.cachedPrograms.delete",
        "options.iris.cachedPrograms.delete.confirm",
        "options.iris.cachedPrograms.delete.tooltip",
        "options.iris.cachedPrograms.deleted"
    );

    @Test
    void everyCacheControlKeyIsDefinedInTheEnglishLanguageFile() throws IOException {
        final Path languageFile = englishLanguageFile();

        final Set<String> defined = new HashSet<>();
        for (String line : Files.readAllLines(languageFile, StandardCharsets.UTF_8)) {
            final String trimmedLine = line.strip();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }
            final int separator = trimmedLine.indexOf('=');
            if (separator > 0) {
                defined.add(trimmedLine.substring(0, separator).strip());
            }
        }

        for (String key : KEYS) {
            assertTrue(defined.contains(key), "The English language file is missing " + key);
        }
    }

    @Test
    void theKeysTheControlsReturnAreCoveredByTheCheckedList() {
        final Set<String> covered = Set.copyOf(KEYS);

        for (boolean driverSupportsProgramBinary : new boolean[] { true, false }) {
            for (boolean stored : new boolean[] { true, false }) {
                final ShaderPackCacheLogic.Toggle toggle =
                    ShaderPackCacheLogic.savedProgramsToggle(driverSupportsProgramBinary, stored);
                assertTrue(covered.contains(toggle.labelKey()),
                    "the switch label " + toggle.labelKey() + " is not in the checked key list");
                for (String tooltipKey : toggle.tooltipKeys()) {
                    assertTrue(covered.contains(tooltipKey),
                        "the switch tooltip key " + tooltipKey + " is not in the checked key list");
                }
            }
        }

        for (boolean confirmationPending : new boolean[] { true, false }) {
            final String labelKey = ShaderPackCacheLogic.deleteButtonLabelKey(confirmationPending);
            assertTrue(covered.contains(labelKey), "the delete label " + labelKey + " is not in the checked key list");
        }
        for (String tooltipKey : ShaderPackCacheLogic.deleteTooltipKeys()) {
            assertTrue(covered.contains(tooltipKey), "the delete tooltip key " + tooltipKey + " is not in the checked key list");
        }
        assertTrue(covered.contains(ShaderPackCacheLogic.deletedNotificationKey()),
            "the deletion announcement is not in the checked key list");
    }

    /** Locates the English language file through the root project the test task is started from. */
    private static Path englishLanguageFile() {
        final String projectRoot = System.getProperty("actinium.projectRoot");
        if (projectRoot == null) {
            fail("Missing system property actinium.projectRoot - run tests via the root Gradle project");
        }
        final Path languageFile = Path.of(projectRoot, "src", "main", "resources", "assets", "iris", "lang", "en_us.lang");
        assertTrue(Files.isRegularFile(languageFile), "Missing language file " + languageFile);
        return languageFile;
    }
}
