package com.gtnewhorizons.angelica.glsm.hooks;

import com.gtnewhorizons.angelica.glsm.hooks.events.AlphaStateChangeEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.BlendFuncChangeEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.FogStateChangeEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.ForeignDrawEndEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.LightmapCoordsEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.LoadingCheckpointEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.ProgramChangeEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.ProgramDeleteEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.ShaderColorChangeEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.TextureBindEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.TextureDeleteEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.TextureUnitStateEvent;
import com.gtnewhorizons.angelica.glsm.hooks.events.VanillaBlendChangeEvent;
import net.minecraftforge.eventbus.api.bus.EventBus;

public final class GLSMHooks {
    public static DeferredBlendHandler blendHandler;
    public static DeferredAlphaHandler alphaHandler;
    public static DeferredDepthColorHandler depthColorHandler;
    /** Optional host-owned observer for diagnostics at the native draw boundary. */
    public static DrawCallObserver drawCallObserver;
    public static ShaderWorkSubmitter shaderWorkSubmitter;
    public static ShaderTransformPostProcessor postTransformProcessor;
    public static PerFrameUniformBlock perFrameUniformBlock;
    public static PerFrameUniformBlock perPassUniformBlock;

    /**
     * Optional host callback notified after each executed {@code GL_TEXTURE_2D} bind request,
     * including cache hits. The host keeps vanilla's {@code GlStateManager.TEXTURES[].textureName}
     * mirror in sync for mods that read it reflectively to restore a prior binding.
     */
    public static volatile TextureBindSyncCallback textureBindSyncCallback;

    /** Receives texture binds so the host can synchronize state outside GLSM. */
    public interface TextureBindSyncCallback {
        /** Reports the active texture unit and the texture id passed to the executed bind request. */
        void onTextureBound(int textureUnit, int textureId);
    }

    /** Dispatches a texture bind to the host callback when one has been registered. */
    public static void notifyTextureBindSync(int textureUnit, int textureId) {
        final TextureBindSyncCallback callback = textureBindSyncCallback;
        if (callback != null) {
            callback.onTextureBound(textureUnit, textureId);
        }
    }

    /**
     * Optional host callback notified once the state manager runs on a different display context than the one
     * its GL names were created on. Everything keyed on the previous context - the memoized driver
     * fingerprint of the program-binary cache and the pool of retained program names - is stale from that
     * moment on, and dropping it needs the shader module, which GLSM cannot reach. The root module registers
     * the callback because it is the only one that sees both sides.
     */
    public static volatile DisplayContextChangeListener displayContextChangeListener;

    /** Receives the fact that the display context the managed GL objects belong to has changed. */
    public interface DisplayContextChangeListener {
        /** Called once per detected change, after the state manager adopted the new identity. */
        void onDisplayContextChange();
    }

    /** Dispatches a display-context change to the host callback when one has been registered. */
    public static void notifyDisplayContextChange() {
        final DisplayContextChangeListener listener = displayContextChangeListener;
        if (listener != null) {
            listener.onDisplayContextChange();
        }
    }

    /** Escape hatch: -Dactinium.glsmHooksAlwaysActive=true forces the consumer gate on. */
    private static final boolean ALWAYS_ACTIVE = Boolean.getBoolean("actinium.glsmHooksAlwaysActive");

    /**
     * Cheap gate for per-call snapshot/event work in GLStateManager hot paths. Written by the
     * pipeline host (Iris PipelineManager): true while a consumer with observable side effects
     * (a DeferredWorldRenderingPipeline) is active, false otherwise. Defaults to true so behavior
     * is unchanged when no host maintains it.
     */
    public static volatile boolean consumersActive = true;

    /** Whether hook consumers may act on events, i.e. snapshots and event posts must run. */
    public static boolean hasActiveConsumers() {
        return ALWAYS_ACTIVE || consumersActive;
    }

    public static final EventBus<TextureBindEvent> TEXTURE_BIND = EventBus.create(TextureBindEvent.class);
    public static final EventBus<TextureDeleteEvent> TEXTURE_DELETE = EventBus.create(TextureDeleteEvent.class);
    public static final EventBus<TextureUnitStateEvent> TEXTURE_UNIT_STATE = EventBus.create(TextureUnitStateEvent.class);
    public static final EventBus<ProgramChangeEvent> PROGRAM_CHANGE = EventBus.create(ProgramChangeEvent.class);
    public static final EventBus<ProgramDeleteEvent> PROGRAM_DELETE = EventBus.create(ProgramDeleteEvent.class);
    public static final EventBus<AlphaStateChangeEvent> ALPHA_STATE_CHANGE = EventBus.create(AlphaStateChangeEvent.class);
    public static final EventBus<ShaderColorChangeEvent> SHADER_COLOR_CHANGE = EventBus.create(ShaderColorChangeEvent.class);
    public static final EventBus<ForeignDrawEndEvent> FOREIGN_DRAW_END = EventBus.create(ForeignDrawEndEvent.class);
    public static final EventBus<VanillaBlendChangeEvent> VANILLA_BLEND_CHANGE = EventBus.create(VanillaBlendChangeEvent.class);
    public static final EventBus<BlendFuncChangeEvent> BLEND_FUNC_CHANGE = EventBus.create(BlendFuncChangeEvent.class);
    public static final EventBus<FogStateChangeEvent> FOG_STATE_CHANGE = EventBus.create(FogStateChangeEvent.class);
    public static final EventBus<LightmapCoordsEvent> LIGHTMAP_COORDS = EventBus.create(LightmapCoordsEvent.class);
    public static final EventBus<LoadingCheckpointEvent> LOADING_CHECKPOINT = EventBus.create(LoadingCheckpointEvent.class);

    public static final TextureBindEvent textureBindEvent = new TextureBindEvent();
    public static final TextureDeleteEvent textureDeleteEvent = new TextureDeleteEvent();
    public static final TextureUnitStateEvent textureUnitStateEvent = new TextureUnitStateEvent();
    public static final ProgramChangeEvent programChangeEvent = new ProgramChangeEvent();
    public static final ProgramDeleteEvent programDeleteEvent = new ProgramDeleteEvent();
    public static final AlphaStateChangeEvent alphaStateChangeEvent = new AlphaStateChangeEvent();
    public static final ShaderColorChangeEvent shaderColorChangeEvent = new ShaderColorChangeEvent();
    public static final ForeignDrawEndEvent foreignDrawEndEvent = new ForeignDrawEndEvent();
    public static final VanillaBlendChangeEvent vanillaBlendChangeEvent = new VanillaBlendChangeEvent();
    public static final BlendFuncChangeEvent blendFuncChangeEvent = new BlendFuncChangeEvent();
    public static final FogStateChangeEvent fogStateChangeEvent = new FogStateChangeEvent();
    public static final LightmapCoordsEvent lightmapCoordsEvent = new LightmapCoordsEvent();
    public static final LoadingCheckpointEvent loadingCheckpointEvent = new LoadingCheckpointEvent();

    private GLSMHooks() {
    }
}
