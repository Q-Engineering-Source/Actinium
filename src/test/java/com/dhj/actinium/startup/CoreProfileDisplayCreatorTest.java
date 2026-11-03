package com.dhj.actinium.startup;

import com.dhj.actinium.debug.OpenGlVersion;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.junit.jupiter.api.Test;
import org.lwjgl.LWJGLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreProfileDisplayCreatorTest {

    private static final String REQUIRED_VERSION = RenderSystem.MIN_CORE_VERSION / 10
        + "." + RenderSystem.MIN_CORE_VERSION % 10;

    @Test
    void rejectionLineQuotesTheDriverAnswerTheGpuAndTheRequirement() {
        String rejection = CoreProfileDisplayCreator.describeRejection(
            4,
            6,
            "3.1 Mesa 24.0.3",
            false,
            "llvmpipe (LLVM 17.0.6, 256 bits)"
        );

        assertTrue(rejection.contains("requested OpenGL 4.6 core profile"), rejection);
        assertTrue(rejection.contains("driver returned \"3.1 Mesa 24.0.3\""), rejection);
        assertTrue(rejection.contains("core profile: false"), rejection);
        assertTrue(rejection.contains("GPU: llvmpipe (LLVM 17.0.6, 256 bits)"), rejection);
        assertTrue(rejection.contains("OpenGL " + REQUIRED_VERSION + " core or newer is required"), rejection);
    }

    @Test
    void failureMessageReportsTheDriverAnswerBeforeTheLastError() {
        String rejection = CoreProfileDisplayCreator.describeRejection(4, 6, "3.3.0", true, "NVIDIA");
        String failure = CoreProfileDisplayCreator.describeFailure(
            rejection,
            new LWJGLException("Pixel format not accelerated")
        );

        assertTrue(failure.contains("Last driver answer: " + rejection), failure);
        assertTrue(failure.contains("Last error: Pixel format not accelerated."), failure);
        assertTrue(
            failure.indexOf("Last driver answer") < failure.indexOf("Last error"),
            "the driver answer must precede the error so the report reads as the reason then the symptom: " + failure
        );
        assertTrue(failure.endsWith(" Update or install the GPU driver."), failure);
    }

    @Test
    void failureMessageOmitsTheDriverAnswerWhenNoContextWasCreated() {
        String failure = CoreProfileDisplayCreator.describeFailure(
            null,
            new LWJGLException("Pixel format not accelerated")
        );

        assertFalse(failure.contains("Last driver answer"), failure);
        assertTrue(failure.contains("Last error: Pixel format not accelerated."), failure);
    }

    @Test
    void driverAnswerInsideTheSupportedRangeBecomesTheNextCeiling() {
        assertEquals(
            45,
            capAfter(46, "4.5.0 NVIDIA 551.61"),
            "a driver answering 4.5 to a 4.6 request leaves 4.5 as the highest version worth retrying"
        );
    }

    @Test
    void driverAnswerBelowTheRequirementLeavesTheLowestSupportedVersionToProbe() {
        assertEquals(
            RenderSystem.MIN_CORE_VERSION,
            capAfter(46, "3.1 Mesa 24.0.3"),
            "versions between the answer and the requirement cannot work, so the scan resumes at the requirement"
        );
        assertEquals(
            RenderSystem.MIN_CORE_VERSION,
            capAfter(46, "3.2.1 NVIDIA 331.38"),
            "a 3.2 answer still leaves 3.3 untested"
        );
    }

    @Test
    void rejectedLowestSupportedRequestEndsTheScan() {
        assertTrue(
            capAfter(RenderSystem.MIN_CORE_VERSION, "3.1 Mesa 24.0.3") < RenderSystem.MIN_CORE_VERSION,
            "no supported core version remains, so the loop stops instead of requesting a version below the floor"
        );
    }

    @Test
    void unreadableDriverVersionStepsDownOneVersionOnly() {
        assertEquals(
            45,
            RenderSystem.probeCapAfterRejection(46, -1),
            "an unparsable GL_VERSION carries no capability information, so only the rejected request is dropped"
        );
    }

    @Test
    void profileStringPacksMajorAndMinor() {
        assertEquals(45, CoreProfileDisplayCreator.parseGlProfile("4.5"));
        assertEquals(40, CoreProfileDisplayCreator.parseGlProfile("4."), "a trailing dot means a .0 minor");
        assertEquals(30, CoreProfileDisplayCreator.parseGlProfile("3"), "a bare number means a .0 minor");
    }

    @Test
    void unparsableProfileFallsBackToTheConfiguredVersion() {
        assertEquals(-1, CoreProfileDisplayCreator.parseGlProfile("4.x"));
        assertEquals(-1, CoreProfileDisplayCreator.parseGlProfile(""));
        assertEquals(-1, CoreProfileDisplayCreator.parseGlProfile("OpenGL 4.5"));
        assertEquals(-1, CoreProfileDisplayCreator.parseGlProfile("4.5.1"), "the minor part must be a single number");
    }

    private static int capAfter(int requested, String driverVersion) {
        return RenderSystem.probeCapAfterRejection(requested, packed(OpenGlVersion.parse(driverVersion)));
    }

    private static int packed(OpenGlVersion version) {
        return version.major() * 10 + version.minor();
    }
}
