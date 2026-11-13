package com.gtnewhorizons.angelica.sdlgpu.resource;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.device.FenceWait;
import com.gtnewhorizons.angelica.sdlgpu.device.Submits;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.sdl.SDLGPU.*;

/** Transfers packed image bytes while preserving the distinction between array layers and volume slices. */
public final class TextureTransfers {
    private TextureTransfers() {}

    public static boolean layered(int target) {
        return target == GL13.GL_TEXTURE_CUBE_MAP || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL30.GL_TEXTURE_1D_ARRAY;
    }

    public static int mipDepth(ResourceManager.TextureMeta meta, int level) {
        if (meta.glTarget() == GL30.GL_TEXTURE_1D_ARRAY) return meta.height();
        return meta.glTarget() == GL12.GL_TEXTURE_3D ? PixelOps.mipLevelSize(meta.depth(), level) : meta.depth();
    }

    public static int mipHeight(ResourceManager.TextureMeta meta, int level) {
        return meta.glTarget() == GL30.GL_TEXTURE_1D_ARRAY ? 1 : PixelOps.mipLevelSize(meta.height(), level);
    }

    public static void upload(Device device, FrameManager frames, ResourceManager resources, int texture, int level,
        int x, int y, int z, int width, int height, int depth, ByteBuffer bytes) {
        final ResourceManager.TextureMeta meta = resources.getTextureMeta(texture);
        final long handle = resources.getTextureHandle(texture);
        if (meta == null || handle == 0L) throw new IllegalStateException("Texture " + texture + " has no storage");
        final long copyPass = frames.ensureCopyPass();
        if (copyPass == 0L) throw new IllegalStateException("Texture upload requires an active frame");
        resources.flushBatchedUploads(copyPass);
        final long transfer = createTransfer(device, bytes.remaining(), SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final ByteBuffer mapped = SDL_MapGPUTransferBuffer(device.getDevice(), transfer, false, bytes.remaining());
            if (mapped == null) throw failure("map texture upload buffer");
            try {
                MemoryUtil.memCopy(bytes, mapped);
            } finally {
                SDL_UnmapGPUTransferBuffer(device.getDevice(), transfer);
            }
            final SDL_GPUTextureTransferInfo source = SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(transfer);
            final SDL_GPUTextureRegion destination = SDL_GPUTextureRegion.calloc(stack).texture(handle).mip_level(level)
                .x(x).y(y).w(width).h(height);
            if (layered(meta.glTarget())) {
                final int sliceBytes = bytes.remaining() / depth;
                for (int layer = 0; layer < depth; layer++) {
                    source.offset(layer * sliceBytes);
                    destination.layer(z + layer).z(0).d(1);
                    SDL_UploadToGPUTexture(copyPass, source, destination, false);
                }
            } else {
                destination.z(z).d(depth);
                SDL_UploadToGPUTexture(copyPass, source, destination, false);
            }
            resources.markTextureWritten(handle);
        } finally {
            SDL_ReleaseGPUTransferBuffer(device.getDevice(), transfer);
        }
    }

    public static void download(Device device, ResourceManager resources, int texture, int level, int firstLayer,
        int width, int height, int depth, ByteBuffer output) {
        final ResourceManager.TextureMeta meta = resources.getTextureMeta(texture);
        final long handle = resources.getTextureHandle(texture);
        if (meta == null || handle == 0L) throw new IllegalStateException("Texture " + texture + " has no storage");
        final long transfer = createTransfer(device, output.remaining(), SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long command = SDL_AcquireGPUCommandBuffer(device.getDevice());
            if (command == 0L) throw failure("acquire texture readback command buffer");
            final long pass = SDL_BeginGPUCopyPass(command);
            if (pass == 0L) {
                SDL_CancelGPUCommandBuffer(command);
                throw failure("begin texture readback pass");
            }
            final SDL_GPUTextureRegion source = SDL_GPUTextureRegion.calloc(stack).texture(handle).mip_level(level)
                .w(width).h(height);
            final SDL_GPUTextureTransferInfo destination = SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(transfer);
            if (layered(meta.glTarget())) {
                final int sliceBytes = output.remaining() / depth;
                for (int layer = 0; layer < depth; layer++) {
                    source.layer(firstLayer + layer).z(0).d(1);
                    destination.offset(layer * sliceBytes);
                    SDL_DownloadFromGPUTexture(pass, source, destination);
                }
            } else {
                source.z(firstLayer).d(depth);
                SDL_DownloadFromGPUTexture(pass, source, destination);
            }
            SDL_EndGPUCopyPass(pass);
            final long fence = Submits.submitAndAcquireFence(command);
            if (fence == 0L) throw failure("submit texture readback");
            try {
                FenceWait.await(device, fence, FenceWait.FOREVER);
            } finally {
                device.fenceReleaser().release(fence);
            }
            final ByteBuffer mapped = SDL_MapGPUTransferBuffer(device.getDevice(), transfer, false, output.remaining());
            if (mapped == null) throw failure("map texture readback buffer");
            try {
                MemoryUtil.memCopy(mapped, output);
            } finally {
                SDL_UnmapGPUTransferBuffer(device.getDevice(), transfer);
            }
        } finally {
            SDL_ReleaseGPUTransferBuffer(device.getDevice(), transfer);
        }
    }

    private static long createTransfer(Device device, int bytes, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long transfer = SDL_CreateGPUTransferBuffer(device.getDevice(), SDL_GPUTransferBufferCreateInfo.calloc(stack)
                .usage(usage).size(bytes));
            if (transfer == 0L) throw failure("create texture transfer buffer");
            return transfer;
        }
    }

    private static IllegalStateException failure(String operation) {
        return new IllegalStateException("Failed to " + operation + ": " + SDLError.SDL_GetError());
    }
}
