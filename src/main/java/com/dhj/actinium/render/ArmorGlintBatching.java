package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.rendering.tesr.GlintCapture;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelSkeleton;
import net.minecraft.client.model.ModelZombie;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/** Reuses vanilla armor geometry while leaving the glint block's state changes to Minecraft. */
public final class ArmorGlintBatching {
    private static final ModelPartBatcher BATCHER = ModelPartBatcher.INSTANCE;
    private static ArmorGlintBatching queued;

    private final ModelBase model;
    private final Entity entity;
    private final GlintCapture base = new GlintCapture();

    private ArmorGlintBatching(ModelBase model, Entity entity) {
        this.model = model;
        this.entity = entity;
    }

    public static boolean isWorldArmor() {
        return RenderingState.isRenderingWorld() && !GuiGlStateBoundary.isEntityGuiSurface();
    }

    public static boolean isWorldGlintEnabled() {
        return isWorldArmor() && AngelicaConfig.glintWorld();
    }

    private static boolean isVanillaArmorModel(ModelBase model) {
        Class<?> type = model.getClass();
        return type == ModelBiped.class || type == ModelSkeleton.class || type == ModelZombie.class;
    }

    public static ArmorGlintBatching captureBase(ModelBase model, Entity entity, Runnable draw) {
        if (!isWorldGlintEnabled() || !BATCHER.isActive() || !isVanillaArmorModel(model)) {
            draw.run();
            return null;
        }
        ArmorGlintBatching armor = new ArmorGlintBatching(model, entity);
        if (!BATCHER.beginArmorCapture(armor.base)) {
            draw.run();
            return null;
        }
        drawCaptured(armor.base, draw);
        return armor;
    }

    public void renderGlint(ModelBase model, Entity entity, ResourceLocation texture, Runnable draw) {
        ArmorGlintBatching previous = queued;
        try {
            if (this.model == model && this.entity == entity && GLSMCurrentAttribs.getOverlayA() == 0.0F
                && BATCHER.queueSkippedArmorGlint(base, texture)) {
                queued = this;
            }
            draw.run();
        } finally {
            queued = previous;
            base.reset();
        }
    }

    public static boolean isQueued(ModelBase model, Entity entity) {
        return queued != null && queued.model == model && queued.entity == entity;
    }

    public static GlintCapture captureFirstLayer(ModelBase model, Runnable draw) {
        if (isWorldGlintEnabled() && isVanillaArmorModel(model) && BATCHER.canCaptureGlint()) {
            GlintCapture capture = new GlintCapture();
            if (BATCHER.beginGlintCapture(capture)) {
                drawCaptured(capture, draw);
                return capture;
            }
        }
        draw.run();
        return null;
    }

    public static boolean replaySecondLayer(GlintCapture capture) {
        if (capture == null) return false;
        try {
            return BATCHER.replayGlint(capture);
        } finally {
            capture.reset();
        }
    }

    private static void drawCaptured(GlintCapture capture, Runnable draw) {
        boolean completed = false;
        try {
            draw.run();
            completed = true;
        } finally {
            if (completed) {
                capture.end();
            } else {
                capture.reset();
            }
        }
    }
}
