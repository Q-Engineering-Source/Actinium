package net.coderbot.iris.debug;

import net.coderbot.iris.gl.texture.DepthBufferFormat;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderMemoryDiagnosticsTest {
    @Test
    void colorTextureEstimateUsesBytesPerPixelForInternalFormat() {
        assertEquals(1920L * 1080L * 8L,
            ShaderMemoryDiagnostics.estimateColorTextureBytes(InternalTextureFormat.RGBA16F, 1920, 1080));
        assertEquals(17L * 9L * 3L,
            ShaderMemoryDiagnostics.estimateColorTextureBytes(InternalTextureFormat.RGB8, 17, 9));
    }

    @Test
    void depthTextureEstimateUsesBytesPerPixelForDepthFormat() {
        assertEquals(640L * 480L * 4L,
            ShaderMemoryDiagnostics.estimateDepthTextureBytes(DepthBufferFormat.DEPTH24_STENCIL8, 640, 480));
        assertEquals(13L * 7L * 8L,
            ShaderMemoryDiagnostics.estimateDepthTextureBytes(DepthBufferFormat.DEPTH32F_STENCIL8, 13, 7));
    }

    @Test
    void rgba8MipEstimateIncludesEveryLevelThroughRequestedMaximum() {
        // 8x4 + 4x2 + 2x1 pixels, four bytes each.
        assertEquals((8L * 4L + 4L * 2L + 2L) * 4L,
            ShaderMemoryDiagnostics.estimateMipmappedRgba8Bytes(8, 4, 2));
    }
}
