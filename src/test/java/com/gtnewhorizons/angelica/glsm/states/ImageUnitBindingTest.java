package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageUnitBindingTest {

    @Test
    void freshBindingReportsTheDefaultsTheDriverReports() {
        final ImageUnitBinding binding = new ImageUnitBinding();
        assertEquals(0, binding.getTexture());
        assertEquals(GL15.GL_READ_ONLY, binding.getAccess());
        assertEquals(GL30.GL_R8, binding.getFormat());
    }

    @Test
    void resetRestoresDefaultsAfterABind() {
        final ImageUnitBinding binding = new ImageUnitBinding();
        binding.setBinding(7, 2, true, 3, GL15.GL_READ_WRITE, GL30.GL_RGBA8);
        binding.reset();
        assertEquals(0, binding.getTexture());
        assertEquals(0, binding.getLevel());
        assertFalse(binding.isLayered());
        assertEquals(GL15.GL_READ_ONLY, binding.getAccess());
        assertEquals(GL30.GL_R8, binding.getFormat());
    }

    @Test
    void copyKeepsTheBoundState() {
        final ImageUnitBinding binding = new ImageUnitBinding();
        binding.setBinding(GL11.GL_TEXTURE_2D, 0, true, 1, GL15.GL_READ_WRITE, GL30.GL_RGBA8);
        final ImageUnitBinding copy = binding.copy();
        assertTrue(copy.sameAs(binding));
        assertEquals(GL11.GL_TEXTURE_2D, copy.getTexture());
        assertEquals(GL30.GL_RGBA8, copy.getFormat());
    }
}
