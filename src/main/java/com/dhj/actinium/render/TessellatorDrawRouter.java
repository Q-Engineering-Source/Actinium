package com.dhj.actinium.render;

import com.dhj.actinium.render.terrain.TileEntityBatchDrawGuard;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizons.angelica.client.rendering.DeferredDrawBatcher;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import net.minecraft.client.renderer.BufferBuilder;

/**
 * Routes one {@code Tessellator.draw()} call to the path that owns the vertices it holds.
 *
 * <p>The routes are exclusive: whichever one takes the draw also resets the builder, so the caller
 * must skip vanilla's upload.</p>
 *
 * <p>A batch reads the vertices itself and finishes the builder when it is done, so the draw has to
 * stay open until then. Finishing it first makes the streaming draw throw "not tesselating", which
 * is why the batch route runs before any {@code finishDrawing()} call.</p>
 */
public final class TessellatorDrawRouter {

    private TessellatorDrawRouter() {
    }

    /**
     * @param view the same builder as the shared eight-int vertex view the batch reads
     * @return whether the draw was handled here; {@code false} means vanilla's own path has to run
     */
    public static boolean draw(BufferBuilder buffer, ITessellatorData view) {
        if (VanillaBufferBuilderRenderer.shouldUseVanillaPositionDraw(buffer)) {
            return false;
        }
        if (DeferredDrawBatcher.capture(buffer)) {
            return true;
        }
        if (TessellatorManager.shouldInterceptBufferBuilderDraw()) {
            buffer.finishDrawing();
            TessellatorManager.interceptBufferBuilderDraw(buffer);
            return true;
        }
        if (ModelPartBatcher.isBatchingActive()) {
            ModelPartBatcher.drawTessellator(view);
            return true;
        }
        buffer.finishDrawing();
        VanillaBufferBuilderRenderer.draw(buffer, "Tessellator");
        return true;
    }
}
