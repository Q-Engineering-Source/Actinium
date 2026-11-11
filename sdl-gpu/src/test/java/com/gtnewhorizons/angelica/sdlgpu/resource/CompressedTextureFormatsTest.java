package com.gtnewhorizons.angelica.sdlgpu.resource;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompressedTextureFormatsTest {
    @Test
    void partialMipDimensionsStillTransferCompleteBlocksForEveryLayer() {
        final int bc1 = EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
        final int bc3 = EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
        assertEquals(8, CompressedTextureFormats.byteSize(bc1, 1, 1, 1));
        assertEquals(32, CompressedTextureFormats.byteSize(bc1, 5, 7, 1));
        assertEquals(64 * 3, CompressedTextureFormats.byteSize(bc3, 5, 7, 3));
        assertEquals(0, CompressedTextureFormats.byteSize(bc3, 0, 7, 3));
    }

    @Test
    void regionCanUsePartialBlocksOnlyAtTheMipBoundary() {
        assertDoesNotThrow(() -> CompressedTextureFormats.checkRegion(4, 4, 3, 2, 7, 6));
        assertDoesNotThrow(() -> CompressedTextureFormats.checkRegion(0, 0, 4, 4, 7, 6));
        assertThrows(IllegalArgumentException.class, () -> CompressedTextureFormats.checkRegion(1, 0, 4, 4, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> CompressedTextureFormats.checkRegion(0, 0, 3, 4, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> CompressedTextureFormats.checkRegion(4, 4, 5, 4, 8, 8));
    }

    @Test
    void rejectsUncompressedFormatsAndOverflowBeforeNativeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> CompressedTextureFormats.byteSize(GL30.GL_RGBA32F, 4, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> CompressedTextureFormats.byteSize(GL30.GL_COMPRESSED_RG_RGTC2, -1, 4, 1));
        assertThrows(ArithmeticException.class, () -> CompressedTextureFormats.byteSize(GL30.GL_COMPRESSED_RG_RGTC2, Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> ResourceManager.mapTextureFormat(GL30.GL_COMPRESSED_SIGNED_RED_RGTC1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ResourceManager.mapTextureFormat(GL30.GL_COMPRESSED_SIGNED_RG_RGTC2, 0, 0));
    }
}
