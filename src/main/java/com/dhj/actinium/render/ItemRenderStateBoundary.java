package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.opengl.GL11;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;

/**
 * Isolates item renderers that execute arbitrary fixed-function GL code from the shader pipeline.
 */
public final class ItemRenderStateBoundary {
    private ItemRenderStateBoundary() {
    }

    /**
     * Saves the tracked GL state and defers shader-pipeline synchronization for a built-in renderer.
     */
    public static void begin() {
        GLSMAttribStack.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GLStateManager.beginForeignDraw();
    }

    /**
     * Restores the tracked GL state before allowing the shader pipeline to resynchronize.
     */
    public static void end() {
        try {
            GLSMAttribStack.glPopAttrib();
        } finally {
            GLStateManager.endForeignDraw();
        }
    }
}
