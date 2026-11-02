package dhj.embeddedt.embeddium.api.options.structure;

import dhj.embeddedt.embeddium.api.options.OptionIdentifier;

public final class StandardOptions {
    private static final String EMBEDDIUM_MOD_ID = "embeddium";
    private static final String ACTINIUM_MOD_NAME = "actinium";
    
    public static class Group {
        public static final OptionIdentifier<Void> RENDERING = OptionIdentifier.create("minecraft", "rendering");
        public static final OptionIdentifier<Void> WINDOW = OptionIdentifier.create("minecraft", "window");
        public static final OptionIdentifier<Void> INDICATORS = OptionIdentifier.create("minecraft", "indicators");
        public static final OptionIdentifier<Void> GRAPHICS = OptionIdentifier.create("minecraft", "graphics");
        public static final OptionIdentifier<Void> MIPMAPS = OptionIdentifier.create("minecraft", "mipmaps");
        public static final OptionIdentifier<Void> DETAILS = OptionIdentifier.create("minecraft", "details");
        public static final OptionIdentifier<Void> CHUNK_UPDATES = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "chunk_updates");
        public static final OptionIdentifier<Void> RENDERING_CULLING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "rendering_culling");
        public static final OptionIdentifier<Void> CPU_SAVING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "cpu_saving");
        public static final OptionIdentifier<Void> SORTING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "sorting");
        public static final OptionIdentifier<Void> LIGHTING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "lighting");
        public static final OptionIdentifier<Void> ACTINIUM_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "debug");
    }

    public static class Pages {
        // The settings pages are host-owned: they live under the actinium
        // namespace so the RSO tab rail groups them with the host mod rather
        // than with the celeritas compatibility bridge.
        public static final OptionIdentifier<Void> GENERAL = OptionIdentifier.create(ACTINIUM_MOD_NAME, "general");
        public static final OptionIdentifier<Void> QUALITY = OptionIdentifier.create(ACTINIUM_MOD_NAME, "quality");
        public static final OptionIdentifier<Void> PERFORMANCE = OptionIdentifier.create(ACTINIUM_MOD_NAME, "performance");
        public static final OptionIdentifier<Void> ADVANCED = OptionIdentifier.create(ACTINIUM_MOD_NAME, "advanced");
        public static final OptionIdentifier<Void> DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "debug");
        public static final OptionIdentifier<Void> SHADERS = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "shaders");
    }

    public static class Option {
        public static final OptionIdentifier<Void> RENDER_DISTANCE = OptionIdentifier.create("minecraft", "render_distance");
        public static final OptionIdentifier<Void> SIMULATION_DISTANCE = OptionIdentifier.create("minecraft", "simulation_distance");
        public static final OptionIdentifier<Void> BRIGHTNESS = OptionIdentifier.create("minecraft", "brightness");
        public static final OptionIdentifier<Void> GUI_SCALE = OptionIdentifier.create("minecraft", "gui_scale");
        public static final OptionIdentifier<Void> FULLSCREEN = OptionIdentifier.create("minecraft", "fullscreen");
        public static final OptionIdentifier<Void> FULLSCREEN_MODE = OptionIdentifier.create(ACTINIUM_MOD_NAME, "fullscreen_mode");
        public static final OptionIdentifier<Void> FULLSCREEN_RESOLUTION = OptionIdentifier.create(ACTINIUM_MOD_NAME, "fullscreen_resolution");
        public static final OptionIdentifier<Void> VSYNC = OptionIdentifier.create("minecraft", "vsync");
        public static final OptionIdentifier<Void> MAX_FRAMERATE = OptionIdentifier.create("minecraft", "max_frame_rate");
        public static final OptionIdentifier<Void> VIEW_BOBBING = OptionIdentifier.create("minecraft", "view_bobbing");
        public static final OptionIdentifier<Void> INACTIVITY_FPS_LIMIT = OptionIdentifier.create("minecraft", "inactivity_fps_limit");
        public static final OptionIdentifier<Void> ATTACK_INDICATOR = OptionIdentifier.create("minecraft", "attack_indicator");
        public static final OptionIdentifier<Void> AUTOSAVE_INDICATOR = OptionIdentifier.create("minecraft", "autosave_indicator");
        public static final OptionIdentifier<Void> GRAPHICS_MODE = OptionIdentifier.create("minecraft", "graphics_mode");
        public static final OptionIdentifier<Void> CLOUDS = OptionIdentifier.create("minecraft", "clouds");
        public static final OptionIdentifier<Void> WEATHER = OptionIdentifier.create("minecraft", "weather");
        public static final OptionIdentifier<Void> LEAVES = OptionIdentifier.create("minecraft", "leaves");
        public static final OptionIdentifier<Void> PARTICLES = OptionIdentifier.create("minecraft", "particles");
        public static final OptionIdentifier<Void> SMOOTH_LIGHT = OptionIdentifier.create("minecraft", "smooth_lighting");
        public static final OptionIdentifier<Void> BIOME_BLEND = OptionIdentifier.create("minecraft", "biome_blend");
        public static final OptionIdentifier<Void> ENTITY_DISTANCE = OptionIdentifier.create("minecraft", "entity_distance");
        public static final OptionIdentifier<Void> ENTITY_SHADOWS = OptionIdentifier.create("minecraft", "entity_shadows");
        public static final OptionIdentifier<Void> VIGNETTE = OptionIdentifier.create("minecraft", "vignette");
        public static final OptionIdentifier<Void> DYNAMIC_FOV = OptionIdentifier.create(ACTINIUM_MOD_NAME, "dynamic_fov");
        public static final OptionIdentifier<Void> MIPMAP_LEVEL = OptionIdentifier.create("minecraft", "mipmap_levels");
        public static final OptionIdentifier<Void> CHUNK_UPDATE_THREADS = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "chunk_update_threads");
        public static final OptionIdentifier<Void> DEFFER_CHUNK_UPDATES = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "defer_chunk_updates");
        public static final OptionIdentifier<Void> BLOCK_FACE_CULLING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "block_face_culling");
        public static final OptionIdentifier<Void> COMPACT_VERTEX_FORMAT = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "compact_vertex_format");
        public static final OptionIdentifier<Void> FOG_OCCLUSION = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "fog_occlusion");
        public static final OptionIdentifier<Void> ENTITY_CULLING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "entity_culling");
        public static final OptionIdentifier<Void> ANIMATE_VISIBLE_TEXTURES = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "animate_only_visible_textures");
        public static final OptionIdentifier<Void> NO_ERROR_CONTEXT = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "no_error_context");
        public static final OptionIdentifier<Void> PERSISTENT_MAPPING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "persistent_mapping");
        public static final OptionIdentifier<Void> CPU_FRAMES_AHEAD = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "cpu_render_ahead_limit");
        public static final OptionIdentifier<Void> MULTIDRAW_MODE = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "multidraw_mode");
        public static final OptionIdentifier<Void> STREAMING_UPLOAD_STRATEGY = OptionIdentifier.create(ACTINIUM_MOD_NAME, "streaming_upload_strategy");
        public static final OptionIdentifier<Void> DEFERRED_PARTICLE_BATCHING = OptionIdentifier.create(ACTINIUM_MOD_NAME, "deferred_particle_batching");
        public static final OptionIdentifier<Void> ALLOW_DIRECT_MEMORY_ACCESS = OptionIdentifier.create(ACTINIUM_MOD_NAME, "allow_direct_memory_access");
        public static final OptionIdentifier<Void> MODEL_RENDERER_BATCHING = OptionIdentifier.create(ACTINIUM_MOD_NAME, "model_renderer_batching");
        public static final OptionIdentifier<Void> MODEL_RENDERER_DISPLAY_LISTS = OptionIdentifier.create(ACTINIUM_MOD_NAME, "model_renderer_display_lists");
        public static final OptionIdentifier<Void> FAST_LIT_ITEM_RENDERING = OptionIdentifier.create(ACTINIUM_MOD_NAME, "fast_lit_item_rendering");
        public static final OptionIdentifier<Void> FAST_LIT_ITEM_DISPLAY_LISTS = OptionIdentifier.create(ACTINIUM_MOD_NAME, "fast_lit_item_display_lists");
        public static final OptionIdentifier<Void> TRANSLUCENT_FACE_SORTING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "translucent_face_sorting");
        public static final OptionIdentifier<Void> USE_QUAD_NORMALS_FOR_LIGHTING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "use_quad_normals_for_lighting");
        public static final OptionIdentifier<Void> RENDER_PASS_OPTIMIZATION = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "render_pass_optimization");
        public static final OptionIdentifier<Void> RENDER_PASS_CONSOLIDATION = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "render_pass_consolidation");
        public static final OptionIdentifier<Void> USE_FASTER_CLOUDS = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "use_faster_clouds");
        public static final OptionIdentifier<Void> ACTINIUM_PRODUCTION_DIAGNOSTICS = OptionIdentifier.create(ACTINIUM_MOD_NAME, "production_diagnostics");
        public static final OptionIdentifier<Void> ACTINIUM_IGNORE_FRAMEBUFFER_ERRORS = OptionIdentifier.create(ACTINIUM_MOD_NAME, "ignore_framebuffer_errors");
        public static final OptionIdentifier<Void> ACTINIUM_GL_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "gl_debug");
        public static final OptionIdentifier<Void> ACTINIUM_LWJGL_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "lwjgl_debug");
        public static final OptionIdentifier<Void> ACTINIUM_PBR_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "pbr_debug");
        public static final OptionIdentifier<Void> ACTINIUM_CLOUD_CONTROL_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "cloud_control_debug");
        public static final OptionIdentifier<Void> ACTINIUM_PERF_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "perf_debug");
        public static final OptionIdentifier<Void> ACTINIUM_GPU_PERF_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "gpu_perf_debug");
        public static final OptionIdentifier<Void> ACTINIUM_FRAME_GL_ERROR_CHECK = OptionIdentifier.create(ACTINIUM_MOD_NAME, "frame_gl_error_check");
        public static final OptionIdentifier<Void> ACTINIUM_POST_RENDER_GL_ERROR_CHECK = OptionIdentifier.create(ACTINIUM_MOD_NAME, "post_render_gl_error_check");
        public static final OptionIdentifier<Void> ACTINIUM_DISABLE_ERROR_CHECKS = OptionIdentifier.create(ACTINIUM_MOD_NAME, "disable_error_checks");
        public static final OptionIdentifier<Void> ACTINIUM_REDIRECTOR_DEBUG = OptionIdentifier.create(ACTINIUM_MOD_NAME, "redirector_debug");
        public static final OptionIdentifier<Void> ACTINIUM_REDIRECTOR_LOG_SPAM = OptionIdentifier.create(ACTINIUM_MOD_NAME, "redirector_log_spam");
        public static final OptionIdentifier<Void> ACTINIUM_REDIRECTOR_CLASS_DUMP = OptionIdentifier.create(ACTINIUM_MOD_NAME, "redirector_class_dump");
        public static final OptionIdentifier<Void> ASYNC_GRAPH_SEARCH = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "async_graph_search");
        public static final OptionIdentifier<Void> RASTER_OCCLUSION_CULLING = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "raster_occlusion_culling");
        public static final OptionIdentifier<Void> CHUNK_FADE_IN_DURATION = OptionIdentifier.create(EMBEDDIUM_MOD_ID, "chunk_fade_in_duration");
    }
}
