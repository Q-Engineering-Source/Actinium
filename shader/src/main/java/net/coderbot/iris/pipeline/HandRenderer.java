package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.compat.mojang.GameModeUtil;
import com.gtnewhorizons.angelica.compat.mojang.InteractionHand;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import lombok.Getter;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.layer.GbufferPrograms;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

import java.util.Map;



public class HandRenderer {
    public static final HandRenderer INSTANCE = new HandRenderer();

    private boolean ACTIVE;
    private boolean handRenderCancelled;
    private @Getter boolean renderingSolid;
    public static final float DEPTH = 0.125F;

    private void setupGlState(RenderGlobal gameRenderer, Camera camera, float tickDelta) {
        final Minecraft mc = Minecraft.getMinecraft();

        GLStateManager.glMatrixMode(GL11.GL_PROJECTION);
        GLStateManager.glLoadIdentity();
        // We need to scale the matrix by 0.125 so the hand doesn't clip through blocks.
        GLStateManager.glScalef(1.0F, 1.0F, DEPTH);

        // TODO: Anaglyph
        /*if (this.mc.gameSettings.anaglyph) {
            GLStateManager.glTranslatef((float) (-(anaglyphChannel * 2 - 1)) * 0.07F, 0.0F, 0.0F);
        }*/

        if (mc.entityRenderer.cameraZoom != 1.0D) {
            GLStateManager.glTranslatef((float) mc.entityRenderer.cameraYaw, (float) (-mc.entityRenderer.cameraPitch), 0.0F);
            GLStateManager.glScaled(mc.entityRenderer.cameraZoom, mc.entityRenderer.cameraZoom, 1.0D);
        }

        Project.gluPerspective(mc.entityRenderer.getFOVModifier(tickDelta, false), (float) mc.displayWidth / (float) mc.displayHeight, 0.05F, mc.entityRenderer.farPlaneDistance * 2.0F);


        if (mc.playerController.isSpectatorMode()) {
            GLStateManager.glScalef(1.0F, 2 / 3f, 1.0F);
        }

        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glLoadIdentity();

        // TODO: Anaglyph
        /*if (mc.gameSettings.anaglyph) {
            GL11.glTranslatef((float) (anaglyphChannel * 2 - 1) * 0.1F, 0.0F, 0.0F);
        }*/

        mc.entityRenderer.hurtCameraEffect(tickDelta);

        if (mc.gameSettings.viewBobbing) {
            mc.entityRenderer.applyBobbing(tickDelta);
        }
    }

    private boolean canRender(Camera camera, RenderGlobal gameRenderer) {
        Minecraft mc = Minecraft.getMinecraft();

        return mc.entityRenderer.debugViewDirection <= 0 &&
               mc.gameSettings.thirdPersonView == 0 &&
               !camera.isSleeping() &&
               !mc.gameSettings.hideGUI &&
               !GameModeUtil.isSpectator() &&
               !mc.playerController.isSpectatorMode();
    }

    private boolean isHandRenderingCancelled(RenderGlobal gameRenderer, float tickDelta) {
        return MinecraftForge.EVENT_BUS.post(new RenderHandEvent(gameRenderer, tickDelta, 0));
    }

    public boolean isHandTranslucent(InteractionHand hand) {
        ItemStack heldItem = hand.getItemInHand(Minecraft.getMinecraft().player);

        if (heldItem == null) return false;
        final Item item = heldItem.getItem();

        if (item instanceof ItemBlock itemBlock) {
            final Map<Block, BlockRenderLayer> blockTypeIds = BlockRenderingSettings.INSTANCE.getBlockTypeIds();
            return blockTypeIds != null && blockTypeIds.get(itemBlock.getBlock()) == BlockRenderLayer.TRANSLUCENT;
        }

        return false;
    }

    public boolean isAnyHandTranslucent() {
        return isHandTranslucent(InteractionHand.MAIN_HAND) || isHandTranslucent(InteractionHand.OFF_HAND);
    }

    public void renderSolid(float tickDelta, Camera camera, RenderGlobal gameRenderer, WorldRenderingPipeline pipeline) {
        IrisGlDebug.markStage("hand-solid:entry");
        if (!canRender(camera, gameRenderer) || !IrisApi.getInstance().isShaderPackInUse()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();

        ACTIVE = true;

        pipeline.setPhase(WorldRenderingPhase.HAND_SOLID);
        IrisGlDebug.markStage("hand-solid:set-phase");

        GLStateManager.glPushMatrix();
        GLStateManager.glDepthMask(true); // actually write to the depth buffer, it's normally disabled at this point

        mc.profiler.startSection("iris_hand");

        setupGlState(gameRenderer, camera, tickDelta);

        GbufferPrograms.setBlockEntityDefaults();

        renderingSolid = true;
        mc.entityRenderer.enableLightmap();
        try {
            this.handRenderCancelled = isHandRenderingCancelled(gameRenderer, tickDelta);
            if (!this.handRenderCancelled) {
                IrisGlDebug.logWorldPassState("before-render-item", WorldRenderingPhase.HAND_SOLID.name(), "hand-solid");
                IrisGlDebug.beginFramebufferSamplePhase("hand-solid-draw");
                try {
                    IrisGlDebug.logCurrentFramebufferSamples("before-render-item", 1);
                    mc.entityRenderer.itemRenderer.renderItemInFirstPerson(tickDelta);
                    IrisGlDebug.markStage("hand-solid:render-item");
                    IrisGlDebug.logCurrentFramebufferSamples("after-render-item", 1);
                    IrisGlDebug.logWorldPassState("after-render-item", WorldRenderingPhase.HAND_SOLID.name(), "hand-solid");
                } finally {
                    IrisGlDebug.endFramebufferSamplePhase();
                }
            }
        } finally {
            mc.entityRenderer.disableLightmap();
        }

        GLStateManager.defaultBlendFunc();
        GLStateManager.glDepthMask(false);
        GLStateManager.glPopMatrix();

        mc.profiler.endSection();

        resetProjectionMatrix();

        renderingSolid = false;

        pipeline.setPhase(WorldRenderingPhase.NONE);
        IrisGlDebug.markStage("hand-solid:end");

        ACTIVE = false;
    }

    // TODO: RenderType
    public void renderTranslucent(float tickDelta, Camera camera, RenderGlobal gameRenderer, WorldRenderingPipeline pipeline) {
        IrisGlDebug.markStage("hand-translucent:entry");
        if (!canRender(camera, gameRenderer) || !isAnyHandTranslucent() || !IrisApi.getInstance().isShaderPackInUse()) {
            return;
        }
        if (this.handRenderCancelled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();

        ACTIVE = true;

        pipeline.setPhase(WorldRenderingPhase.HAND_TRANSLUCENT);
        IrisGlDebug.markStage("hand-translucent:set-phase");

        GLStateManager.glPushMatrix();

        mc.profiler.startSection("iris_hand_translucent");

        setupGlState(gameRenderer, camera, tickDelta);

        GbufferPrograms.setBlockEntityDefaults();

        IrisGlDebug.logWorldPassState("before-render-item", WorldRenderingPhase.HAND_TRANSLUCENT.name(), "hand-translucent");
        IrisGlDebug.beginFramebufferSamplePhase("hand-translucent-draw");
        try {
            IrisGlDebug.logCurrentFramebufferSamples("before-render-item", 1);
            mc.entityRenderer.enableLightmap();
            mc.entityRenderer.itemRenderer.renderItemInFirstPerson(tickDelta);
            IrisGlDebug.markStage("hand-translucent:render-item");
            IrisGlDebug.logCurrentFramebufferSamples("after-render-item", 1);
            IrisGlDebug.logWorldPassState("after-render-item", WorldRenderingPhase.HAND_TRANSLUCENT.name(), "hand-translucent");
        } finally {
            IrisGlDebug.endFramebufferSamplePhase();
            mc.entityRenderer.disableLightmap();
        }

        GLStateManager.glPopMatrix();

        resetProjectionMatrix();

        Minecraft.getMinecraft().profiler.endSection();

        pipeline.setPhase(WorldRenderingPhase.NONE);
        IrisGlDebug.markStage("hand-translucent:end");

        ACTIVE = false;
    }

    private void resetProjectionMatrix() {
        GLStateManager.glMatrixMode(GL11.GL_PROJECTION);
        GLStateManager.glLoadIdentity();
        GLStateManager.glMultMatrix(RenderingState.INSTANCE.getProjectionBuffer());
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
    }

    public boolean isActive() {
        return ACTIVE;
    }
}
