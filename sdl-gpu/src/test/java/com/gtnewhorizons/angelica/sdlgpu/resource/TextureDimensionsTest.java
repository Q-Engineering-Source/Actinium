package com.gtnewhorizons.angelica.sdlgpu.resource;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextureDimensionsTest {
    private static ResourceManager.TextureMeta meta(int target, int width, int height, int depth) {
        return new ResourceManager.TextureMeta(target, 0, 0, width, height, depth, 4, 0);
    }

    @Test
    void volumeDepthShrinksWhileArrayAndCubeLayerCountsStayFixed() {
        final ResourceManager.TextureMeta volume = meta(GL12.GL_TEXTURE_3D, 32, 16, 8);
        final ResourceManager.TextureMeta array = meta(GL30.GL_TEXTURE_2D_ARRAY, 32, 16, 8);
        final ResourceManager.TextureMeta cube = meta(GL13.GL_TEXTURE_CUBE_MAP, 32, 32, 6);
        assertEquals(2, TextureTransfers.mipDepth(volume, 2));
        assertEquals(8, TextureTransfers.mipDepth(array, 2));
        assertEquals(6, TextureTransfers.mipDepth(cube, 2));
        assertEquals(4, TextureTransfers.mipHeight(volume, 2));
        assertEquals(4, TextureTransfers.mipHeight(array, 2));
    }

    @Test
    void oneDimensionalArrayStoresItsGlHeightAsTheNativeLayerCount() {
        final ResourceManager.TextureMeta array = meta(GL30.GL_TEXTURE_1D_ARRAY, 32, 11, 1);
        assertEquals(1, TextureTransfers.mipHeight(array, 0));
        assertEquals(1, TextureTransfers.mipHeight(array, 3));
        assertEquals(11, TextureTransfers.mipDepth(array, 0));
        assertEquals(11, TextureTransfers.mipDepth(array, 3));
    }
}
