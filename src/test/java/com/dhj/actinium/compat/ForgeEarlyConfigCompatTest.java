package com.dhj.actinium.compat;

import net.minecraftforge.common.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeEarlyConfigCompatTest {
    @Test
    void persistsRestoredProfileWithoutReadingBackTheCoreProfile(@TempDir Path directory) {
        Configuration configuration = new Configuration(directory.resolve("forge.cfg").toFile());
        configuration.get("early", "openglVersionMajor", 4).setValue(4);
        configuration.get("early", "openglVersionMinor", 6).setValue(6);
        configuration.get("early", "openglCompatProfile", false).setValue(false);
        configuration.get("early", "openglDebugContext", true).setValue(true);
        configuration.get("early", "windowCentered", false).setValue(false);
        configuration.get("general", "removeErroringEntities", true).setValue(true);
        configuration.save();
        ForgeEarlyConfigCompat.Snapshot restored = new ForgeEarlyConfigCompat.Snapshot(3, 3, true, false);
        ForgeEarlyConfigCompat.Snapshot original = ForgeEarlyConfigCompat.snapshot();

        try {
            ForgeEarlyConfigCompat.restore(restored);
            ForgeEarlyConfigCompat.persistOutlandsProfile(configuration, restored);

            assertEquals(restored, ForgeEarlyConfigCompat.snapshot());
            Configuration reread = new Configuration(configuration.getConfigFile());
            assertEquals(
                restored,
                new ForgeEarlyConfigCompat.Snapshot(
                    reread.get("early", "openglVersionMajor", 0).getInt(),
                    reread.get("early", "openglVersionMinor", 0).getInt(),
                    reread.get("early", "openglCompatProfile", false).getBoolean(),
                    reread.get("early", "openglDebugContext", true).getBoolean()
                )
            );
            assertFalse(reread.get("early", "windowCentered", true).getBoolean());
            assertTrue(reread.get("general", "removeErroringEntities", false).getBoolean());
        } finally {
            ForgeEarlyConfigCompat.restore(original);
        }
    }

    @Test
    void restoresEverySettingFromTheSnapshot() {
        ForgeEarlyConfigCompat.Snapshot original = ForgeEarlyConfigCompat.snapshot();
        ForgeEarlyConfigCompat.Snapshot requested = new ForgeEarlyConfigCompat.Snapshot(3, 2, false, true);

        try {
            ForgeEarlyConfigCompat.restore(requested);
            ForgeEarlyConfigCompat.applyCoreProfile(4, 1, false);
            ForgeEarlyConfigCompat.restore(requested);

            assertEquals(requested, ForgeEarlyConfigCompat.snapshot());
        } finally {
            ForgeEarlyConfigCompat.restore(original);
        }
    }
}
