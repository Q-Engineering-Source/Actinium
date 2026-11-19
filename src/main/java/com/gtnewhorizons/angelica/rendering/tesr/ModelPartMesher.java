package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.Tessellator;

import java.util.List;

public final class ModelPartMesher {

    private ModelPartMesher() {}

    public static void emitPart(DirectTessellator t, ModelRenderer part, float scale) {
        t.setTranslation(
            part.offsetX + part.rotationPointX * scale,
            part.offsetY + part.rotationPointY * scale,
            part.offsetZ + part.rotationPointZ * scale);
        emitPartLocal(t, part, scale);
        t.setTranslation(0, 0, 0);
    }

    /** Renders the part's boxes into the shared buffer the active capture reads its vertices from. */
    public static void emitPartLocal(DirectTessellator t, ModelRenderer part, float scale) {
        final List<ModelBox> boxes = part.cubeList;
        for (int i = 0, n = boxes.size(); i < n; i++) {
            boxes.get(i).render(Tessellator.getInstance().getBuffer(), scale);
        }
    }
}
