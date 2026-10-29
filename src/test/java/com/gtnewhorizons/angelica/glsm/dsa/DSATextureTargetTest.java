package com.gtnewhorizons.angelica.glsm.dsa;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DSATextureTargetTest {
    @Test
    void eachTextureTargetUsesItsOwnBindingQuery() {
        final int[][] queries = {
            {GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_BINDING_1D},
            {GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BINDING_2D},
            {GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_BINDING_3D},
            {GL13.GL_TEXTURE_CUBE_MAP, GL13.GL_TEXTURE_BINDING_CUBE_MAP},
            {GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_BINDING_1D_ARRAY},
            {GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_BINDING_2D_ARRAY},
            {GL31.GL_TEXTURE_RECTANGLE, GL31.GL_TEXTURE_BINDING_RECTANGLE},
            {GL31.GL_TEXTURE_BUFFER, GL31.GL_TEXTURE_BINDING_BUFFER},
            {GL32.GL_TEXTURE_2D_MULTISAMPLE, GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE},
            {GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY, GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY},
            {GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY}
        };
        for (int[] pair : queries) assertEquals(pair[1], DSAUnsupported.bindingQuery(pair[0]));
        assertThrows(IllegalArgumentException.class, () -> DSAUnsupported.bindingQuery(GL11.GL_PROXY_TEXTURE_2D));
    }

    @Test
    void allCubeFacesShareCubeMapBinding() {
        for (int face = GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X; face <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z; face++) {
            assertEquals(GL13.GL_TEXTURE_CUBE_MAP, DSAUnsupported.bindingTarget(face));
            assertEquals(GL13.GL_TEXTURE_BINDING_CUBE_MAP, DSAUnsupported.bindingQuery(face));
        }
    }
}
