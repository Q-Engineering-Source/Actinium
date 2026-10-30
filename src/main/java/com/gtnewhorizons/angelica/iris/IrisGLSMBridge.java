package com.gtnewhorizons.angelica.iris;

import com.gtnewhorizons.angelica.client.rendering.TextureTracker;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredAlphaHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredBlendHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredDepthColorHandler;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaBooleanLayer;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaStateLayer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.coderbot.iris.Iris;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gbuffer_overrides.state.StateTracker;
import net.coderbot.iris.gl.blending.AlphaTestStorage;
import net.coderbot.iris.gl.blending.BlendModeStorage;
import net.coderbot.iris.gl.blending.DepthColorStorage;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.gl.state.StateUpdateNotifiers;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.coderbot.iris.vertices.ImmediateState;

public final class IrisGLSMBridge {
    private static Runnable alphaFuncListener;
    private static Runnable alphaTestListener;
    private static Runnable blendFuncListener;
    private static Runnable fogModeListener;
    private static Runnable fogStartListener;
    private static Runnable fogEndListener;
    private static Runnable fogDensityListener;
    private static Runnable colorModulatorListener;
    private static boolean registered;
    private static boolean inputsDeferred;
    private static boolean blendDeferred;

    static {
        StateUpdateNotifiers.alphaFuncNotifier = listener -> alphaFuncListener = listener;
        StateUpdateNotifiers.alphaTestNotifier = listener -> alphaTestListener = listener;
        StateUpdateNotifiers.blendFuncNotifier = listener -> blendFuncListener = listener;
        StateUpdateNotifiers.fogModeNotifier = listener -> fogModeListener = listener;
        StateUpdateNotifiers.fogStartNotifier = listener -> fogStartListener = listener;
        StateUpdateNotifiers.fogEndNotifier = listener -> fogEndListener = listener;
        StateUpdateNotifiers.fogDensityNotifier = listener -> fogDensityListener = listener;
        StateUpdateNotifiers.colorModulatorNotifier = listener -> colorModulatorListener = listener;
    }

    private IrisGLSMBridge() {
    }

    private static VanillaBooleanLayer gated(VanillaBooleanLayer layer) {
        return new VanillaBooleanLayer() {
            @Override
            public boolean isOverrideHeld() {
                return Iris.enabled && layer.isOverrideHeld();
            }

            @Override
            public boolean getVanilla() {
                return layer.getVanilla();
            }

            @Override
            public void setVanilla(boolean enabled) {
                layer.setVanilla(enabled);
            }
        };
    }

    private static <T> VanillaStateLayer<T> gated(VanillaStateLayer<T> layer) {
        return new VanillaStateLayer<>() {
            @Override
            public boolean isOverrideHeld() {
                return Iris.enabled && layer.isOverrideHeld();
            }

            @Override
            public void readVanilla(T into) {
                layer.readVanilla(into);
            }

            @Override
            public void writeVanilla(T from) {
                layer.writeVanilla(from);
            }
        };
    }

    private static void refreshBlendCondition() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof DeferredWorldRenderingPipeline deferredPipeline) {
            deferredPipeline.onVanillaBlendChanged();
        }
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        IrisGlDebug.logDebugInfo("glsm-bridge register defaultTexUnit={} lightmapTexUnit={}", OpenGlHelper.defaultTexUnit, OpenGlHelper.lightmapTexUnit);

        IrisSamplers.initRenderer();

        GLSMHooks.blendHandler = new DeferredBlendHandler() {
            @Override
            public boolean isBlendLocked() {
                return Iris.enabled && BlendModeStorage.isBlendLocked();
            }

            @Override
            public boolean isOverrideHeld() {
                return Iris.enabled && BlendModeStorage.isOverrideHeld();
            }

            @Override
            public void deferBlendModeToggle(boolean enabled) {
                BlendModeStorage.deferBlendModeToggle(enabled);
            }

            @Override
            public void deferBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
                BlendModeStorage.deferBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
            }

            @Override
            public void flushDeferredBlend() {
                BlendModeStorage.flushDeferredBlend();
            }
        };

        GLStateManager.getBlendMode().setVanillaLayer(gated(BlendModeStorage.ENABLE_LAYER));
        GLStateManager.getBlendState().setVanillaLayer(gated(BlendModeStorage.FUNC_LAYER));
        GLStateManager.getAlphaTest().setVanillaLayer(gated(AlphaTestStorage.ENABLE_LAYER));
        GLStateManager.getAlphaState().setVanillaLayer(gated(AlphaTestStorage.FUNC_LAYER));
        GLStateManager.getDepthState().setVanillaLayer(gated(DepthColorStorage.DEPTH_LAYER));
        GLStateManager.getColorMask().setVanillaLayer(gated(DepthColorStorage.COLOR_LAYER));

        GLSMHooks.alphaHandler = new DeferredAlphaHandler() {
            @Override
            public boolean isAlphaTestLocked() {
                return Iris.enabled && AlphaTestStorage.isAlphaTestLocked();
            }

            @Override
            public void deferAlphaTestToggle(boolean enabled) {
                AlphaTestStorage.deferAlphaTestToggle(enabled);
            }

            @Override
            public void deferAlphaFunc(int function, float reference) {
                AlphaTestStorage.deferAlphaFunc(function, reference);
            }
        };

        GLSMHooks.depthColorHandler = new DeferredDepthColorHandler() {
            @Override
            public boolean isDepthColorLocked() {
                return Iris.enabled && DepthColorStorage.isDepthColorLocked();
            }

            @Override
            public boolean isOverrideHeld() {
                return Iris.enabled && DepthColorStorage.isOverrideHeld();
            }

            @Override
            public void deferDepthEnable(boolean enabled) {
                DepthColorStorage.deferDepthEnable(enabled);
            }

            @Override
            public void deferColorMask(boolean r, boolean g, boolean b, boolean a) {
                DepthColorStorage.deferColorMask(r, g, b, a);
            }
        };

        GLSMHooks.ALPHA_STATE_CHANGE.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }
            if (alphaFuncListener != null) {
                alphaFuncListener.run();
            }
            if (alphaTestListener != null) {
                alphaTestListener.run();
            }
        });

        GLSMHooks.SHADER_COLOR_CHANGE.addListener(event -> {
            if (Iris.enabled && colorModulatorListener != null) {
                colorModulatorListener.run();
            }
        });

        GLSMHooks.BLEND_FUNC_CHANGE.addListener(event -> {
            if (Iris.enabled && blendFuncListener != null) {
                blendFuncListener.run();
            }
        });

        GLSMHooks.FOG_STATE_CHANGE.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }
            if (fogModeListener != null) {
                fogModeListener.run();
            }
            if (fogStartListener != null) {
                fogStartListener.run();
            }
            if (fogEndListener != null) {
                fogEndListener.run();
            }
            if (fogDensityListener != null) {
                fogDensityListener.run();
            }
        });

        GLSMHooks.TEXTURE_BIND.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }
            TextureTracker.INSTANCE.onBindTexture();
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (pipeline != null) {
                pipeline.onBindTexture(event.textureId);
            }
        });

        GLSMHooks.TEXTURE_DELETE.addListener(event -> {
            if (Iris.enabled) {
                PBRTextureManager.INSTANCE.onDeleteTexture(event.textureId);
            }
        });

        GLSMHooks.TEXTURE_UNIT_STATE.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }

            boolean updatePipeline = false;
            if (event.unit == IrisSamplers.ALBEDO_TEXTURE_UNIT) {
                StateTracker.INSTANCE.albedoSampler = event.enabled;
                updatePipeline = true;
            } else if (event.unit == IrisSamplers.LIGHTMAP_TEXTURE_UNIT) {
                StateTracker.INSTANCE.lightmapSampler = event.enabled;
                updatePipeline = true;
            }

            if (IrisGlDebug.shouldLogTextureUnitEvents() && (event.unit == IrisSamplers.ALBEDO_TEXTURE_UNIT || event.unit == IrisSamplers.LIGHTMAP_TEXTURE_UNIT)) {
                if (IrisGlDebug.shouldLogGlsmEvent("texture-unit-state:" + event.unit, 16)) {
                    IrisGlDebug.logDebugInfo(
                            "texture-unit-state unit={} target={} enabled={} activeUnit={} albedo={} lightmap={}",
                            event.unit,
                            event.target,
                            event.enabled,
                            GLStateManager.getActiveTextureUnit(),
                            StateTracker.INSTANCE.albedoSampler,
                            StateTracker.INSTANCE.lightmapSampler
                    );
                }
            }

            if (updatePipeline && GLStateManager.isForeignDraw()) {
                inputsDeferred = true;
                return;
            }
            if (updatePipeline) {
                if (IrisGlDebug.shouldLogTextureUnitEvents() && IrisGlDebug.shouldLogGlsmEvent("pipeline-input-update", 16)) {
                    IrisGlDebug.logDebugInfo("pipeline-input-update albedo={} lightmap={}", StateTracker.INSTANCE.albedoSampler, StateTracker.INSTANCE.lightmapSampler);
                }
                Iris.getPipelineManager().getPipeline().ifPresent(pipeline -> pipeline.setInputs(StateTracker.INSTANCE.getInputs()));
            }
        });

        GLSMHooks.VANILLA_BLEND_CHANGE.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }
            if (GLStateManager.isForeignDraw()) {
                blendDeferred = true;
                return;
            }
            refreshBlendCondition();
        });

        GLSMHooks.FOREIGN_DRAW_END.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }
            if (inputsDeferred) {
                inputsDeferred = false;
                Iris.getPipelineManager().getPipeline().ifPresent(pipeline -> pipeline.setInputs(StateTracker.INSTANCE.getInputs()));
            }
            if (blendDeferred) {
                blendDeferred = false;
                refreshBlendCondition();
            }
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (pipeline instanceof DeferredWorldRenderingPipeline deferredPipeline) {
                deferredPipeline.restorePassAfterForeignDraw();
            }
        });

        GLSMHooks.PROGRAM_CHANGE.addListener(event -> {
            if (Iris.enabled && !event.postBind) {
                ProgramUniforms.clearActiveUniforms();
            }
        });

        GLSMHooks.PROGRAM_CHANGE.addListener(event -> {
            if (!Iris.enabled) {
                return;
            }
            if (event.postBind) {
                return;
            }

            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof DeferredWorldRenderingPipeline deferredPipeline)) {
                return;
            }
            boolean shouldOverrideShaders = deferredPipeline.shouldOverrideShaders();
            if (!shouldOverrideShaders) {
                IrisGlDebug.logProgramOverrideDecision(
                        "program-change-skip",
                        deferredPipeline.getPhase().name(),
                        event.previousProgram,
                        event.newProgram,
                        deferredPipeline.getActivePassProgramId(),
                        false,
                        ImmediateState.isRenderingLevel,
                        DepthColorStorage.isOwnedProgram(event.newProgram),
                        false,
                        false
                );
                return;
            }
            int activePassProgramId = deferredPipeline.getActivePassProgramId();
            boolean ownedProgram = DepthColorStorage.isOwnedProgram(event.newProgram);
            DepthColorStorage.unlockDepthColor();
            if (event.newProgram == 0 || ownedProgram) {
                IrisGlDebug.logProgramOverrideDecision(
                        "program-change-unlock-depth-color",
                        deferredPipeline.getPhase().name(),
                        event.previousProgram,
                        event.newProgram,
                        activePassProgramId,
                        true,
                        ImmediateState.isRenderingLevel,
                        ownedProgram,
                        true,
                        false
                );
            } else {
                IrisGlDebug.logProgramOverrideDecision(
                        "program-change-mod-override",
                        deferredPipeline.getPhase().name(),
                        event.previousProgram,
                        event.newProgram,
                        activePassProgramId,
                        true,
                        ImmediateState.isRenderingLevel,
                        false,
                        false,
                        true
                );
                deferredPipeline.onModProgramOverride();
            }
        });

        GLSMHooks.PROGRAM_CHANGE.addListener(event -> {
            if (!Iris.enabled || !event.postBind) {
                return;
            }

            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof DeferredWorldRenderingPipeline deferredPipeline)
                || !deferredPipeline.shouldOverrideShaders()) {
                return;
            }

            if (GLStateManager.isForeignDraw()) {
                return;
            }
            deferredPipeline.restorePassAfterModProgram(event.newProgram);
        });
    }
}
