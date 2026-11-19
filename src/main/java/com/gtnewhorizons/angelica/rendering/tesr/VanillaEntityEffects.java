package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.api.tesr.TesrMaterial;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.AlphaState;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import com.gtnewhorizons.angelica.glsm.states.ColorMask;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.PipelineManager;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** State shared by vanilla entity effect capture and its batched draw. */
public final class VanillaEntityEffects {
    public static final ResourceLocation SHADOW_TEXTURE = new ResourceLocation("textures/misc/shadow.png");

    private static final AlphaState ALPHA = new AlphaState();
    private static final BlendState BLEND = new BlendState();
    private static final ColorMask COLOR_MASK = new ColorMask();

    private static final TesrMaterial SHADOW = TesrMaterial.builder().translucent().noDepthWrite().stream().build();
    private static final TesrMaterial SHADOW_CUTOUT = TesrMaterial.builder().translucent().noDepthWrite().cutout(0.1f).stream().build();
    private static final TesrMaterial SHADOW_CUTOUT_HALF = TesrMaterial.builder().translucent().noDepthWrite().cutout(0.5f).stream().build();

    private VanillaEntityEffects() {}

    static TesrMaterial material(boolean fire) {
        GLStateManager.requireExitDrawState(true);
        if (!GLStateManager.getTextures().getTextureUnitStates(0).isEnabled()
            || GLSMDepthStencilAlpha.getDepthState().getFunc() != GL11.GL_LEQUAL
            || GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled() != fire) return null;
        final ColorMask mask = GLSMBlend.getEffectiveColorMask(COLOR_MASK);
        if (!mask.red || !mask.green || !mask.blue || !mask.alpha) return null;
        if (fire) {
            if (GLSMBlend.isEffectiveBlendEnabled()) return null;
        } else {
            if (!GLSMBlend.isEffectiveBlendEnabled()) return null;
            final BlendState blend = GLSMBlend.getEffectiveBlendState(BLEND);
            if (blend.getSrcRgb() != GL11.GL_SRC_ALPHA || blend.getDstRgb() != GL11.GL_ONE_MINUS_SRC_ALPHA
                || blend.getSrcAlpha() != GL11.GL_SRC_ALPHA || blend.getDstAlpha() != GL11.GL_ONE_MINUS_SRC_ALPHA
                || blend.getEquationRgb() != GL14.GL_FUNC_ADD || blend.getEquationAlpha() != GL14.GL_FUNC_ADD) return null;
        }

        if (!GLSMDepthStencilAlpha.isEffectiveAlphaTestEnabled()) return fire ? EntityMaterials.SOLID : SHADOW;
        final AlphaState alpha = GLSMDepthStencilAlpha.getEffectiveAlphaState(ALPHA);
        if (alpha.getFunction() != GL11.GL_GREATER) return null;
        if (alpha.getReference() == 0.1f) return fire ? EntityMaterials.CUTOUT : SHADOW_CUTOUT;
        if (alpha.getReference() == 0.5f) return fire ? EntityMaterials.CUTOUT_HALF : SHADOW_CUTOUT_HALF;
        return null;
    }

    public static void preserveMainViewShadow(RenderManager manager, Entity entity, float shadowSize, float shadowOpacity) {
        if (!EntityShadowReplay.isRecording() || manager.options == null || !manager.options.entityShadows
            || !manager.isRenderShadow() || shadowSize <= 0.0f || entity.isInvisible()) return;
        final float shadowAlpha = (float) ((1.0 - manager.getDistanceToCamera(entity.posX, entity.posY, entity.posZ) / 256.0) * shadowOpacity);
        if (shadowAlpha <= 0.0f) return;
        final PipelineManager pipelines = Iris.getPipelineManagerNullable();
        final WorldRenderingPipeline pipeline = pipelines == null ? null : pipelines.getPipelineNullable();
        if (pipeline != null && pipeline.shouldDisableVanillaEntityShadows()) return;

        // Iris turns off projected shadows in its shadow pass. Replaying that pass would omit them.
        EntityShadowReplay.rejectShadowOnly();
    }
}
