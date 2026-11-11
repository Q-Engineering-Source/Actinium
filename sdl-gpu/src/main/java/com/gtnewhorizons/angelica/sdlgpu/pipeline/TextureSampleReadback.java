package com.gtnewhorizons.angelica.sdlgpu.pipeline;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.PixelOps;
import com.gtnewhorizons.angelica.sdlgpu.resource.CompressedTextureFormats;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.TextureTransfers;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.sdl.SDL_GPUColorTargetDescription;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilState;
import org.lwjgl.sdl.SDL_GPUTextureSamplerBinding;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.sdl.SDLGPU.*;

/** Samples compressed images into a floating-point color target so ordinary GL readback receives decoded texels. */
public final class TextureSampleReadback {
    private static final long KEY_SEED = 0x673E924F1AB580CDL;
    private final Device device;
    private final FrameManager frames;
    private final ResourceManager resources;
    private final PipelineStore pipelines;
    private final FullscreenPass fullscreen;
    private final Int2ObjectOpenHashMap<FullscreenPass.Fragment> fragments = new Int2ObjectOpenHashMap<>();

    public TextureSampleReadback(Device device, FrameManager frames, ResourceManager resources, PipelineStore pipelines, FullscreenPass fullscreen) {
        this.device = device;
        this.frames = frames;
        this.resources = resources;
        this.pipelines = pipelines;
        this.fullscreen = fullscreen;
    }

    public void read(ContextState state, int texture, int level, int firstLayer, int depth, ByteBuffer output) {
        final ResourceManager.TextureMeta meta = resources.getTextureMeta(texture);
        final int width = PixelOps.mipLevelSize(meta.width(), level);
        final int height = TextureTransfers.mipHeight(meta, level);
        final boolean srgb = CompressedTextureFormats.isSrgb(meta.glFormat());
        final int variant = meta.glTarget() | (srgb ? 1 << 30 : 0);
        final FullscreenPass.Fragment fragment = fragments.computeIfAbsent(variant, ignored ->
            fullscreen.fragment("decoded-texture-readback-" + variant, 16, () -> source(meta.glTarget(), srgb)));
        final long fragmentShader = fragment.shader();
        if (fragmentShader == 0L) throw new IllegalStateException("Failed to compile compressed texture readback shader");
        final long key = Hashing.fmix64(KEY_SEED, variant);
        final long pipeline = pipelines.getOrBuild(key, () -> createPipeline(fragmentShader));
        if (pipeline == 0L || pipeline == PipelineStore.BAD_PIPELINE_SENTINEL) throw new IllegalStateException("Failed to create compressed texture readback pipeline");
        final long sampler = fullscreen.getNearestSampler();
        if (sampler == 0L) throw new IllegalStateException("Failed to create compressed texture readback sampler");
        final int scratchId = resources.genTexture();
        final long scratch = resources.createTexture(scratchId, GL11.GL_TEXTURE_2D, GL30.GL_RGBA32F, width, height, 1, 1);
        if (scratch == 0L) throw new IllegalStateException("Failed to allocate compressed texture readback target");
        final boolean startedFrame = !frames.isFrameActive();
        if (startedFrame) frames.beginFrame();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_GPUColorTargetInfo.Buffer color = SDL_GPUColorTargetInfo.calloc(1, stack);
            color.get(0).texture(scratch).load_op(SDL_GPU_LOADOP_DONT_CARE).store_op(SDL_GPU_STOREOP_STORE);
            final SDL_GPUTextureSamplerBinding.Buffer binding = SDL_GPUTextureSamplerBinding.calloc(1, stack);
            binding.get(0).texture(resources.getTextureHandle(texture)).sampler(sampler);
            final ByteBuffer parameters = stack.calloc(16);
            final int layerBytes = Math.multiplyExact(Math.multiplyExact(width, height), 16);
            for (int layer = 0; layer < depth; layer++) {
                final long pass = frames.beginRenderPass(color, null);
                if (pass == 0L) throw new IllegalStateException("Failed to begin compressed texture readback pass");
                SDL_BindGPUGraphicsPipeline(pass, pipeline);
                FullscreenPass.setViewport(pass, state, width, height, 0f);
                FullscreenPass.setScissor(pass, state, 0, 0, width, height);
                SDL_BindGPUFragmentSamplers(pass, 0, binding);
                parameters.putInt(0, width).putInt(4, height).putInt(8, firstLayer + layer).putInt(12, level);
                SDL_PushGPUFragmentUniformData(frames.getCommandBuffer(), 0, parameters);
                SDL_DrawGPUPrimitives(pass, 3, 1, 0, 0);
                frames.endRenderPassIfActive(FrameManager.PASS_END_READBACK);
                frames.submitMidFrame();
                TextureTransfers.download(device, resources, scratchId, 0, 0, width, height, 1,
                    output.slice(output.position() + layer * layerBytes, layerBytes));
            }
        } finally {
            resources.deleteTexture(scratchId);
            FullscreenPass.markStateClobbered(state);
            state.lastPushedProgramFs = -1;
            if (startedFrame) frames.endFrame();
        }
    }

    private long createPipeline(long fragment) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_GPUColorTargetDescription.Buffer targets = SDL_GPUColorTargetDescription.calloc(1, stack);
            targets.get(0).format(SDL_GPU_TEXTUREFORMAT_R32G32B32A32_FLOAT);
            targets.get(0).blend_state().color_write_mask((byte) (SDL_GPU_COLORCOMPONENT_R | SDL_GPU_COLORCOMPONENT_G | SDL_GPU_COLORCOMPONENT_B | SDL_GPU_COLORCOMPONENT_A));
            return fullscreen.createPipeline(pipelines, stack, fragment, targets, 1, SDL_GPUDepthStencilState.calloc(stack), 0, "decoded-texture-readback");
        }
    }

    private static String source(int target, boolean srgb) {
        final String sampler = switch (target) {
            case GL12.GL_TEXTURE_3D -> "sampler3D";
            case GL13.GL_TEXTURE_CUBE_MAP -> "samplerCube";
            case GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY -> "sampler2DArray";
            default -> "sampler2D";
        };
        final String fetch = switch (target) {
            case GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY ->
                "texelFetch(sourceTexture, ivec3(ivec2(gl_FragCoord.xy), parameters.z), parameters.w)";
            case GL13.GL_TEXTURE_CUBE_MAP -> "textureLod(sourceTexture, cubeDirection(gl_FragCoord.xy / vec2(parameters.xy), parameters.z), float(parameters.w))";
            default -> "texelFetch(sourceTexture, ivec2(gl_FragCoord.xy), parameters.w)";
        };
        return "#version 460 core\nuniform " + sampler + " sourceTexture;\nuniform ivec4 parameters;\nlayout(location=0) out vec4 color;\n" + """
            vec3 cubeDirection(vec2 uv, int face) {
                vec2 p = uv * 2.0 - 1.0;
                if (face == 0) return vec3(1.0, -p.y, -p.x);
                if (face == 1) return vec3(-1.0, -p.y, p.x);
                if (face == 2) return vec3(p.x, 1.0, p.y);
                if (face == 3) return vec3(p.x, -1.0, -p.y);
                if (face == 4) return vec3(p.x, -p.y, 1.0);
                return vec3(-p.x, -p.y, -1.0);
            }
            """ + "void main() { color = " + fetch + "; "
            + (srgb ? "color.rgb = mix(color.rgb * 12.92, 1.055 * pow(max(color.rgb, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, greaterThan(color.rgb, vec3(0.0031308))); " : "")
            + "}\n";
    }
}
