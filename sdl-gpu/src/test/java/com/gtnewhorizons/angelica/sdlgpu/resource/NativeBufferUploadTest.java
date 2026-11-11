package com.gtnewhorizons.angelica.sdlgpu.resource;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_GPUBufferRegion;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferLocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_InitSubSystem;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

/** Exercises the production upload arena against a real SDL GPU device without creating a game window. */
@Tag("sdl-gpu-device")
class NativeBufferUploadTest {
    private static Device device;
    private static FrameManager frames;
    private static ResourceManager resources;
    private static boolean videoInitialized;
    private static int nextBufferId = 1;

    @BeforeAll
    static void createDevice() {
        videoInitialized = SDL_InitSubSystem(SDL_INIT_VIDEO);
        assertTrue(videoInitialized, SDLError::SDL_GetError);
        device = new Device();
        assertTrue(device.createDevice(), SDLError::SDL_GetError);
        frames = new FrameManager(device);
        resources = new ResourceManager(device, frames);
        frames.setResourceManager(resources);
        frames.setBeforeEndCopyPassCallback(() -> resources.flushBatchedUploads(frames.getCopyPass()));
    }

    @AfterAll
    static void releaseDevice() {
        try {
            if (device != null && device.getDevice() != 0L) {
                SDL_WaitForGPUIdle(device.getDevice());
                if (frames != null && frames.isFrameActive()) frames.endFrame();
                if (frames != null) frames.releaseAllRegisteredFrames();
                if (resources != null) resources.shutdown();
                device.shutdown();
            }
        } finally {
            if (videoInitialized) SDL_QuitSubSystem(SDL_INIT_VIDEO);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {257, 65536, 167936})
    void uploadsAndPatchesSurviveLaterArenaSubmissions(int size) {
        final int id = nextBufferId++;
        final ByteBuffer initial = MemoryUtil.memAlloc(size);
        final ByteBuffer patch = MemoryUtil.memAlloc(size / 3);
        final int patchOffset = size / 4;
        try {
            for (int i = 0; i < size; i++) initial.put(i, (byte) (i * 37 + 11));
            for (int i = 0; i < patch.capacity(); i++) patch.put(i, (byte) (i * 13 + 91));
            final long buffer = resources.createBuffer(id, SDL_GPU_BUFFERUSAGE_VERTEX, size);
            assertTrue(buffer != 0L, SDLError::SDL_GetError);

            upload(initial, buffer, 0L);
            upload(patch, buffer, patchOffset);
            for (int i = 0; i < 4; i++) {
                final int churnId = nextBufferId++;
                final long churn = resources.createBuffer(churnId, SDL_GPU_BUFFERUSAGE_VERTEX, size);
                assertTrue(churn != 0L, SDLError::SDL_GetError);
                upload(initial, churn, 0L);
                resources.deleteBuffer(churnId);
            }

            final ByteBuffer actual = download(buffer, size);
            try {
                for (int i = 0; i < size; i++) {
                    final byte expected = i >= patchOffset && i < patchOffset + patch.capacity()
                        ? patch.get(i - patchOffset) : initial.get(i);
                    assertEquals(expected, actual.get(i), "GPU buffer byte " + i);
                }
            } finally {
                MemoryUtil.memFree(actual);
            }
        } finally {
            resources.deleteBuffer(id);
            MemoryUtil.memFree(initial);
            MemoryUtil.memFree(patch);
        }
    }

    private static void upload(ByteBuffer bytes, long buffer, long offset) {
        frames.beginFrame();
        frames.frame().swapchainUnavailable = true;
        assertTrue(resources.arenaUpload(bytes, buffer, offset, false));
        resources.flushUploadArena();
        frames.endFrame();
    }

    private static ByteBuffer download(long buffer, int size) {
        final long handle = device.getDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long transfer = SDL_CreateGPUTransferBuffer(handle, SDL_GPUTransferBufferCreateInfo.calloc(stack)
                .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD).size(size));
            assertTrue(transfer != 0L, SDLError::SDL_GetError);
            try {
                final long command = SDL_AcquireGPUCommandBuffer(handle);
                assertTrue(command != 0L, SDLError::SDL_GetError);
                final long copyPass = SDL_BeginGPUCopyPass(command);
                assertTrue(copyPass != 0L, SDLError::SDL_GetError);
                SDL_DownloadFromGPUBuffer(copyPass,
                    SDL_GPUBufferRegion.calloc(stack).buffer(buffer).offset(0).size(size),
                    SDL_GPUTransferBufferLocation.calloc(stack).transfer_buffer(transfer).offset(0));
                SDL_EndGPUCopyPass(copyPass);
                final long fence = SDL_SubmitGPUCommandBufferAndAcquireFence(command);
                assertTrue(fence != 0L, SDLError::SDL_GetError);
                try {
                    assertTrue(SDL_WaitForGPUFences(handle, true, stack.pointers(fence)), SDLError::SDL_GetError);
                } finally {
                    SDL_ReleaseGPUFence(handle, fence);
                }
                final ByteBuffer mapped = SDL_MapGPUTransferBuffer(handle, transfer, false, size);
                assertNotNull(mapped, SDLError::SDL_GetError);
                try {
                    final ByteBuffer result = MemoryUtil.memAlloc(size);
                    MemoryUtil.memCopy(mapped, result);
                    return result;
                } finally {
                    SDL_UnmapGPUTransferBuffer(handle, transfer);
                }
            } finally {
                SDL_ReleaseGPUTransferBuffer(handle, transfer);
            }
        }
    }
}
