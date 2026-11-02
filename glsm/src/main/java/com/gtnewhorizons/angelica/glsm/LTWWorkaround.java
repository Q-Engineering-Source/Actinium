package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.states.PixelStoreState;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Workaround for the LTW (OpenLTW) mobile GL wrapper: a null-pixels BGRA glTexImage2D allocation
 * leaves the texture incomplete there, so a 1x1 RGBA texel is uploaded to force allocation.
 */
public final class LTWWorkaround {

    private LTWWorkaround() {}

    private static ByteBuffer oneTexel;

    public static boolean isLtwVersionString(String glVersion) {
        return glVersion != null && glVersion.contains("LTW");
    }

    public static void onTexImage2D(int target, int level, int format, boolean hasPixels) {
        if (target != GL11.GL_TEXTURE_2D || level != 0 || format != GL12.GL_BGRA || hasPixels) return;
        if (oneTexel == null) {
            oneTexel = BufferUtils.createByteBuffer(4);
        }
        oneTexel.clear();
        GLStateManager.forcePixelUnpackState(PixelStoreState.DEFAULT);
        GLStateManager.suspendPixelUnpackBuffer();
        RENDER_BACKEND.texSubImage2D(target, level, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, oneTexel);
        GLStateManager.restorePixelUnpackBuffer();
        GLStateManager.restorePixelUnpackState(PixelStoreState.DEFAULT);
    }
}
