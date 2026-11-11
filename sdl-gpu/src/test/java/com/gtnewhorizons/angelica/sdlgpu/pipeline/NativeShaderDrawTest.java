package com.gtnewhorizons.angelica.sdlgpu.pipeline;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.TextureTransfers;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_GPUColorTargetDescription;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_InitSubSystem;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

/** Compiles a program through the production shader linker and draws its uniform color into an offscreen target. */
@Tag("sdl-gpu-device")
class NativeShaderDrawTest {
    @Test
    void compiledProgramDrawsUniformColorAndScissorIntoOffscreenFramebuffer() {
        assertTrue(SDL_InitSubSystem(SDL_INIT_VIDEO), SDLError::SDL_GetError);
        final Device device = new Device();
        FrameManager frames = null;
        ResourceManager resources = null;
        ShaderManager shaders = null;
        FullscreenPass fullscreen = null;
        PipelineStore pipelines = null;
        long pipeline = 0L;
        final ContextState state = new ContextState();
        final ByteBuffer color = MemoryUtil.memAlloc(16);
        final ByteBuffer pixels = MemoryUtil.memAlloc(8 * 8 * 4);
        try {
            assertTrue(device.createDevice(), SDLError::SDL_GetError);
            frames = new FrameManager(device);
            resources = new ResourceManager(device, frames);
            frames.setResourceManager(resources);
            shaders = new ShaderManager(device);
            fullscreen = new FullscreenPass(shaders, device);
            pipelines = new PipelineStore(device);
            final FullscreenPass.Fragment fragment = fullscreen.fragment("offscreen-test", 16, () -> """
                #version 460 core
                uniform vec4 tint;
                layout(location = 0) out vec4 color;
                void main() { color = tint; }
                """);
            final long fragmentShader = fragment.shader();
            assertTrue(fragmentShader != 0L, "production shader compilation and program linking failed");
            final long texture = resources.createTexture(1, GL11.GL_TEXTURE_2D, GL11.GL_RGBA8, 8, 8, 1, 1);
            assertTrue(texture != 0L, SDLError::SDL_GetError);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                final SDL_GPUColorTargetDescription.Buffer targets = SDL_GPUColorTargetDescription.calloc(1, stack);
                targets.get(0).format(resources.getTextureMeta(1).sdlFormat());
                targets.get(0).blend_state().color_write_mask((byte) (SDL_GPU_COLORCOMPONENT_R | SDL_GPU_COLORCOMPONENT_G | SDL_GPU_COLORCOMPONENT_B | SDL_GPU_COLORCOMPONENT_A));
                pipeline = fullscreen.createPipeline(pipelines, stack, fragmentShader, targets, 1,
                    SDL_GPUDepthStencilState.calloc(stack), 0, "offscreen-test");
                assertNotEquals(0L, pipeline, SDLError::SDL_GetError);
                assertNotEquals(PipelineStore.BAD_PIPELINE_SENTINEL, pipeline, SDLError::SDL_GetError);
                frames.beginFrame();
                frames.frame().swapchainUnavailable = true;
                final SDL_GPUColorTargetInfo.Buffer attachments = SDL_GPUColorTargetInfo.calloc(1, stack);
                attachments.get(0).texture(texture).load_op(SDL_GPU_LOADOP_CLEAR).store_op(SDL_GPU_STOREOP_STORE);
                attachments.get(0).clear_color().r(0f).g(0f).b(0f).a(0f);
                final long render = SDL_BeginGPURenderPass(frames.getCommandBuffer(), attachments, null);
                assertTrue(render != 0L, SDLError::SDL_GetError);
                SDL_BindGPUGraphicsPipeline(render, pipeline);
                FullscreenPass.setViewport(render, state, 8, 8, 0f);
                FullscreenPass.setScissor(render, state, 2, 2, 4, 4);
                color.putFloat(0, 1f).putFloat(4, 0.5f).putFloat(8, 0f).putFloat(12, 1f);
                SDL_PushGPUFragmentUniformData(frames.getCommandBuffer(), 0, color);
                SDL_DrawGPUPrimitives(render, 3, 1, 0, 0);
                SDL_EndGPURenderPass(render);
                frames.endFrame();
            }
            TextureTransfers.download(device, resources, 1, 0, 0, 8, 8, 1, pixels);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    final int offset = (y * 8 + x) * 4;
                    final boolean painted = x >= 2 && x < 6 && y >= 2 && y < 6;
                    assertEquals(painted ? 255 : 0, Byte.toUnsignedInt(pixels.get(offset)), "red at " + x + "," + y);
                    if (painted) {
                        assertEquals(0.5f, Byte.toUnsignedInt(pixels.get(offset + 1)) / 255f, 1f / 255f, "green at " + x + "," + y);
                    } else {
                        assertEquals(0, Byte.toUnsignedInt(pixels.get(offset + 1)), "green outside scissor at " + x + "," + y);
                    }
                    assertEquals(0, Byte.toUnsignedInt(pixels.get(offset + 2)), "blue at " + x + "," + y);
                    assertEquals(painted ? 255 : 0, Byte.toUnsignedInt(pixels.get(offset + 3)), "alpha at " + x + "," + y);
                }
            }
            final ByteBuffer compressed = MemoryUtil.memCalloc(8);
            final ByteBuffer encoded = MemoryUtil.memAlloc(8);
            final ByteBuffer decoded = MemoryUtil.memAlloc(4 * 4 * 16);
            try {
                compressed.putShort(0, (short) 0xF800);
                final long compressedTexture = resources.createTexture(2, GL11.GL_TEXTURE_2D,
                    EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT, 4, 4, 1, 1);
                assertTrue(compressedTexture != 0L, SDLError::SDL_GetError);
                frames.beginFrame();
                TextureTransfers.upload(device, frames, resources, 2, 0, 0, 0, 0, 4, 4, 1, compressed);
                frames.submitMidFrame();
                TextureTransfers.download(device, resources, 2, 0, 0, 4, 4, 1, encoded);
                for (int i = 0; i < compressed.capacity(); i++) assertEquals(compressed.get(i), encoded.get(i), "compressed block byte " + i);
                new TextureSampleReadback(device, frames, resources, pipelines, fullscreen).read(state, 2, 0, 0, 1, decoded);
                for (int i = 0; i < 16; i++) {
                    assertEquals(1f, decoded.getFloat(i * 16), "decoded red texel " + i);
                    assertEquals(0f, decoded.getFloat(i * 16 + 4), "decoded green texel " + i);
                    assertEquals(0f, decoded.getFloat(i * 16 + 8), "decoded blue texel " + i);
                    assertEquals(1f, decoded.getFloat(i * 16 + 12), "decoded alpha texel " + i);
                }
                frames.endFrame();
            } finally {
                MemoryUtil.memFree(compressed);
                MemoryUtil.memFree(encoded);
                MemoryUtil.memFree(decoded);
            }
        } finally {
            if (device.getDevice() != 0L) {
                SDL_WaitForGPUIdle(device.getDevice());
                if (frames != null && frames.isFrameActive()) frames.endFrame();
                if (frames != null) frames.releaseAllRegisteredFrames();
                if (pipeline != 0L && pipeline != PipelineStore.BAD_PIPELINE_SENTINEL) SDL_ReleaseGPUGraphicsPipeline(device.getDevice(), pipeline);
                if (pipelines != null) pipelines.shutdown();
                if (fullscreen != null) fullscreen.shutdown();
                if (shaders != null) shaders.shutdown();
                if (resources != null) resources.shutdown();
                device.shutdown();
            }
            MemoryUtil.nmemFree(state.vboBindingsAddr);
            state.eboBinding.free();
            state.cachedViewport.free();
            state.cachedScissor.free();
            state.cachedBlendColor.free();
            state.fragSamplerBindings.free();
            state.vertSamplerBindings.free();
            MemoryUtil.memFree(color);
            MemoryUtil.memFree(pixels);
            SDL_QuitSubSystem(SDL_INIT_VIDEO);
        }
    }
}
