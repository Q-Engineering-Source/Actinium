package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.CLIENT_ATTRIB_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.popState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.pushState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.restorePixelStore;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.warnOnce;

public final class GLSMAttribStack {

    private GLSMAttribStack() {}

    public static void glPushClientAttrib(int mask) {
        final int slot = ctx().clientAttribStackPointer;
        if (slot >= CLIENT_ATTRIB_STACK_DEPTH) {
            warnOnce("pushclientattrib-overflow", "glPushClientAttrib called with a full client attribute stack ({} deep); ignoring", CLIENT_ATTRIB_STACK_DEPTH);
            return;
        }
        ctx().vaos.saveClientArrays(slot, mask, ctx().boundVAO, ctx().boundVBO);
        ctx().clientAttribSavedTextureUnit[slot] = ctx().clientActiveTextureUnit;
        ctx().clientAttribSavedVertexFlags[slot] = ShaderManager.getInstance().getCurrentVertexFlags();
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            ctx().clientAttribSavedPixelUnpack[slot] = ctx().pixelUnpackState;
            ctx().clientAttribSavedPixelPack[slot] = ctx().pixelPackState;
        }
        ctx().clientAttribStackPointer = slot + 1;
    }

    public static void glPopClientAttrib() {
        if (ctx().clientAttribStackPointer == 0) {
            warnOnce("popclientattrib-underflow", "glPopClientAttrib called with an empty client attribute stack; ignoring");
            return;
        }
        final int slot = --ctx().clientAttribStackPointer;
        final int mask = ctx().vaos.savedClientAttribMask(slot);
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            restorePixelStore(ctx().clientAttribSavedPixelUnpack[slot], ctx().clientAttribSavedPixelPack[slot]);
            ctx().clientAttribSavedPixelUnpack[slot] = null;
            ctx().clientAttribSavedPixelPack[slot] = null;
        }
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) == 0) return;
        ctx().vaos.restoreClientArrays(slot);
        ctx().clientActiveTextureUnit = ctx().clientAttribSavedTextureUnit[slot];
        ShaderManager.getInstance().setCurrentVertexFlags(ctx().clientAttribSavedVertexFlags[slot]);
    }

    public static void glPushAttrib(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPushAttrib(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        GLDebug.pushGroup("pushState");
        pushState(mask);
    }

    public static void glPopAttrib() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPopAttrib();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        GLDebug.popGroup();
        ctx().poppingAttributes = true;
        GLDebug.pushGroup("popState");
        try {
            popState();
        } finally {
            GLDebug.popGroup();
            ctx().poppingAttributes = false;
        }
    }

    public static void glPushAttrib() {
        glPushAttrib(GL11.GL_ENABLE_BIT);
    }
}
