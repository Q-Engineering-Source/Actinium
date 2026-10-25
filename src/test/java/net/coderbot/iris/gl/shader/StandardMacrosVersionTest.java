package net.coderbot.iris.gl.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The ACTINIUM_VERSION define is lexed by jcpp as a C constant. A multi-digit value starting
 * with '0' is treated as an octal constant, and any '8'/'9' digit then raises a LexerException
 * that fails the whole shader pack load (production: alpha-0.0.5-da83c59 -&gt; 000058359).
 */
class StandardMacrosVersionTest {

    @Test
    void letterPrefixedGitShaDoesNotProduceLeadingZeroValue() {
        // alpha-0.0.5-da83c59 -> major=0, minor=0, patch=5, sub=8359. A leading zero
        // ("000058359") would be lexed as an octal constant by jcpp and rejected on the 8/9 digits.
        assertEquals("58359", StandardMacros.formatActiniumVersion("alpha-0.0.5-da83c59"));
    }

    @Test
    void releaseVersionKeepsEstablishedEncoding() {
        assertEquals("10203000", StandardMacros.formatActiniumVersion("1.2.3"));
    }

    @Test
    void versionWithoutNumericTripletFallsBackToZero() {
        assertEquals("0", StandardMacros.formatActiniumVersion("unknown"));
    }

    @Test
    void numericShaSegmentIsNotTreatedAsPrerelease() {
        // A trailing git sha that starts with digits (e.g. "00e287f") must not become sub.
        assertEquals("5000", StandardMacros.formatActiniumVersion("0.0.5-00e287f"));
    }
}
