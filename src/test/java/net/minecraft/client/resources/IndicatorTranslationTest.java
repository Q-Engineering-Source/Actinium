package net.minecraft.client.resources;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IndicatorTranslationTest {
    private static final String LOW_FPS_KEY = "gui.actinium.indicator.row.fps_low";

    @Test
    void percentSignInLowFpsLabelSurvivesLocaleFormatting() throws IOException {
        for (String language : List.of("en_us", "zh_cn", "ru_ru")) {
            Locale locale = loadLocale(language);

            assertEquals("1% Low FPS", locale.formatMessage(LOW_FPS_KEY, new Object[0]), language);
        }
    }

    private static Locale loadLocale(String language) throws IOException {
        String path = "assets/actinium/lang/" + language + ".lang";
        Locale locale = new Locale();
        try (InputStream stream = IndicatorTranslationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf('=');
                    if (separator > 0) {
                        locale.properties.put(line.substring(0, separator), line.substring(separator + 1));
                    }
                }
            }
        }
        return locale;
    }
}
