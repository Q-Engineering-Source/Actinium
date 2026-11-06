package net.coderbot.iris.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directive scan used to split the whole source with {@code \R} and allocate a string per line. It now walks
 * the {@code const} occurrences directly, so the cases below pin the results that must stay identical: several
 * directives on one line are still found, and CRLF, bare CR, LF and Unicode line separators all still delimit.
 */
class ConstDirectiveParserTest {
    @Test
    void reportsOneDirectivePerLine() {
        List<ConstDirectiveParser.ConstDirective> directives = ConstDirectiveParser.findDirectives(
            "const int a = 1; const int b = 2;");

        assertEquals(1, directives.size());
        assertEquals("a", directives.get(0).getKey());
    }

    @Test
    void recognisesEachLineSeparator() {
        for (String separator : List.of("\n", "\r\n", "\r", "\u000B", "\f", "\u0085", "\u2028", "\u2029")) {
            List<ConstDirectiveParser.ConstDirective> directives = ConstDirectiveParser.findDirectives(
                "const int first = 1;" + separator + "const float second = 2.0;");

            assertEquals(2, directives.size(), () -> "separator " + (int) separator.charAt(0));
            assertEquals("first", directives.get(0).getKey());
            assertEquals("second", directives.get(1).getKey());
        }
    }

    @Test
    void ignoresConstTextThatIsNotADirective() {
        List<ConstDirectiveParser.ConstDirective> directives = ConstDirectiveParser.findDirectives("""
            // const int commented = 1;
            uniform float constant;
            const int real = 3;
            """);

        assertEquals(1, directives.size());
        assertEquals("real", directives.get(0).getKey());
    }

    @Test
    void keepsIndentedDirectives() {
        List<ConstDirectiveParser.ConstDirective> directives = ConstDirectiveParser.findDirectives(
            "\t  const int indented = 7;");

        assertEquals(1, directives.size());
        assertEquals("indented", directives.get(0).getKey());
        assertTrue(directives.get(0).getValue().contains("7"));
    }
}
