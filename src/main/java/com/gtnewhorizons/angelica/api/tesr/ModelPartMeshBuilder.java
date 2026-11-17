package com.gtnewhorizons.angelica.api.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import net.minecraft.client.model.ModelRenderer;

/** Geometry for a part that keeps it outside {@code cubeList} */
@FunctionalInterface
public interface ModelPartMeshBuilder {

    void angelica$buildPart(DirectTessellator tess, ModelRenderer part, float scale);
}
