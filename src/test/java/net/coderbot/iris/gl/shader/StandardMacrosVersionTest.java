package net.coderbot.iris.gl.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how ACTINIUM_VERSION is derived from the build version string. The value is lexed by
 * jcpp as a C constant, so it must stay plain decimal: a leading '0' makes jcpp treat the macro
 * as an octal constant, and any '8'/'9' digit then raises a LexerException that fails the whole
 * shader pack load (production: alpha-0.0.5-da83c59).
 */
class StandardMacrosVersionTest {

    @Test
    void releaseVersionKeepsEstablishedEncoding() {
        assertEquals("10203000", format("1.2.3"));
    }

    @Test
    void channelPrefixDoesNotLeakLeadingZeros() {
        assertEquals("14000", format("alpha-0.0.14"));
    }

    @Test
    void prereleaseCounterBecomesSubVersion() {
        assertEquals("5002", format("alpha-0.0.5-beta2"));
        assertEquals("20000008", format("2.0.0-alpha8"));
        assertEquals("10000078", format("1.0.0-beta78-pre"));
    }

    @Test
    void gitShaSuffixNeverBecomesPrereleaseNumber() {
        // alpha-0.0.5-da83c59 previously encoded as 000058359 and hit the jcpp octal rejection.
        assertEquals("5000", format("alpha-0.0.5-da83c59"));
        assertEquals("5000", format("0.0.5-00e287f"));
    }

    @Test
    void devBuildSuffixIsIgnored() {
        assertEquals("14000", format("alpha-0.0.14-26-g32aa1c9a"));
        assertEquals("14000", format("alpha-0.0.14-feat-shader-preprocess-warmup.26+32aa1c9a-dirty"));
        assertEquals("3000", format("alpha-0.0.3-master.7+12857006ab-dirty"));
        assertEquals("20228000", format("2.2.28-fix2117.3+12857006ab"));
    }

    @Test
    void unparseableVersionSortsAboveEveryRealVersion() {
        // Shader packs gate features on ACTINIUM_VERSION comparisons, so an unreadable build
        // version must not make them take an older branch.
        final int unparseable = Integer.parseInt(format("NO-GIT-TAG-SET"));
        assertTrue(unparseable > Integer.parseInt(format("1.2.3")));
        assertTrue(unparseable > Integer.parseInt(format("alpha-0.0.14")));
        assertEquals(unparseable, Integer.parseInt(format("unknown")));
        assertEquals(unparseable, Integer.parseInt(format("moltenvk-v3.4.2-master.1+38af64f10c-dirty")));
    }

    private static String format(String version) {
        final String formatted = StandardMacros.formatActiniumVersion(version);
        assertFalse(formatted.length() > 1 && formatted.charAt(0) == '0',
                "define must not start with '0' or jcpp lexes it as octal: " + formatted);
        return formatted;
    }
}
