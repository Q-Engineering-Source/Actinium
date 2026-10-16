package com.gtnewhorizons.angelica.sdlgpu.frame;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.device.FenceWait;
import com.gtnewhorizons.angelica.sdlgpu.device.Submits;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.Arrays;

import static org.lwjgl.sdl.SDLGPU.SDL_AcquireGPUCommandBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_BeginGPUCopyPass;
import static org.lwjgl.sdl.SDLGPU.SDL_CancelGPUCommandBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_CreateGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_DownloadFromGPUTexture;
import static org.lwjgl.sdl.SDLGPU.SDL_EndGPUCopyPass;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD;
import static org.lwjgl.sdl.SDLGPU.nSDL_MapGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_ReleaseGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_UnmapGPUTransferBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Diagnoses intermittent black frames: reads back the finalTarget's center pixel every frame
 * (asynchronously, so the pipeline is not disturbed) and reports when a single isolated frame
 * comes back black. A sustained run of black pixels means the player is genuinely looking at
 * something dark, so only a run of exactly one is reported.
 */
public final class BlackFrameProbe {

    private static final Logger LOG = LogManager.getLogger("Angelica-SDLGPU");
    private static final int MAX_IN_FLIGHT = 4;

    private final Device device;
    private final ArrayDeque<Slot> slots = new ArrayDeque<>();
    private final RunTracker tracker = new RunTracker();
    private int probeCopyFailures;

    private static final class Slot {
        long xfer;
        long fence;
        long frameNumber;
        Stats stats;
    }

    /** Frame counters captured before FrameManager.endFrame resets them, reported on probe hits. */
    private static final class Stats {
        long frameNumber;
        int boundFbo;
        int swapchainPasses;
        int clearPasses;
        int inPassClears;
        int materializedClears;
        int fboPasses;
        int blits;
        int submits;
        int midFrameSubmits;
        int emptyFrames;
        int stateApplies;
        long gpuWaitNanos;
        long uniformPushBytes;
        int uniformPushes;
        int uniformPushSkips;
        int externalUboPushes;
        int externalUboPushSkips;
        int perFrameBlockPassBreaks;
        final int[] uniformBlockFlushes = new int[2];
        final long[] uniformBlockBytes = new long[2];
        final int[] passEndCauses = new int[FrameManager.PASS_END_CAUSE_COUNT];

        Stats(FrameManager.FrameState f, int boundFbo) {
            frameNumber = f.frameNumber;
            this.boundFbo = boundFbo;
            swapchainPasses = f.swapchainPassesThisFrame;
            clearPasses = f.clearPassesThisFrame;
            inPassClears = f.inPassClearsThisFrame;
            materializedClears = f.materializedClearPassesThisFrame;
            fboPasses = f.fboPassesThisFrame;
            blits = f.blitsThisFrame;
            submits = f.submitsThisFrame;
            midFrameSubmits = f.midFrameSubmitsThisFrame;
            emptyFrames = f.emptyFramesThisFrame;
            stateApplies = f.stateAppliesThisFrame;
            gpuWaitNanos = f.gpuWaitNanosThisFrame;
            uniformPushBytes = f.uniformPushBytesThisFrame;
            uniformPushes = f.uniformPushesThisFrame;
            uniformPushSkips = f.uniformPushSkipsThisFrame;
            externalUboPushes = f.externalUboPushesThisFrame;
            externalUboPushSkips = f.externalUboPushSkipsThisFrame;
            perFrameBlockPassBreaks = f.perFrameBlockPassBreaksThisFrame;
            System.arraycopy(f.uniformBlockFlushesThisFrame, 0, uniformBlockFlushes, 0, 2);
            System.arraycopy(f.uniformBlockBytesThisFrame, 0, uniformBlockBytes, 0, 2);
            System.arraycopy(f.passEndCauseCountsThisFrame, 0, passEndCauses, 0, passEndCauses.length);
        }
    }

    private Stats pending;

    /** Snapshots the frame's counters; call before FrameManager.endFrame resets them. */
    public void captureStats(FrameManager.FrameState f, int boundFbo) {
        pending = new Stats(f, boundFbo);
    }

    /** Turns the pixel stream into report decisions: only an isolated single black frame reports. */
    static final class RunTracker {
        private int run;
        private long lastBlackFrame;

        /** @return the frame number to report, or 0 for none */
        long onPixel(long frameNumber, boolean black) {
            if (black) {
                run++;
                lastBlackFrame = frameNumber;
                return 0;
            }
            final long report = run == 1 ? lastBlackFrame : 0;
            run = 0;
            return report;
        }
    }

    static boolean isBlack(int rgbaLe) {
        // B8G8R8A8 in memory is B,G,R,A; a little-endian int reads B | G<<8 | R<<16 | A<<24.
        final int b = rgbaLe & 0xFF;
        final int g = (rgbaLe >>> 8) & 0xFF;
        final int r = (rgbaLe >>> 16) & 0xFF;
        return r < 8 && g < 8 && b < 8;
    }

    public BlackFrameProbe(Device device) {
        this.device = device;
    }

    public void tick(long finalTargetTex, int width, int height) {
        drainSignaled();
        if (finalTargetTex == 0 || pending == null || slots.size() >= MAX_IN_FLIGHT) return;
        enqueue(finalTargetTex, width / 2, height / 2);
    }

    private void enqueue(long tex, int x, int y) {
        try (var stack = stackPush()) {
            final SDL_GPUTransferBufferCreateInfo xferCi = SDL_GPUTransferBufferCreateInfo.calloc(stack)
                .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD)
                .size(4);
            final long xfer = SDL_CreateGPUTransferBuffer(device.getDevice(), xferCi);
            if (xfer == 0) return;

            // Pre-fill a sentinel so a copy that silently did not run reads differently from a
            // genuinely black pixel.
            final long prefill = nSDL_MapGPUTransferBuffer(device.getDevice(), xfer, false);
            if (prefill != 0) {
                MemoryUtil.memSet(prefill, 0xEE, 4);
                SDL_UnmapGPUTransferBuffer(device.getDevice(), xfer);
            }

            final long cb = SDL_AcquireGPUCommandBuffer(device.getDevice());
            if (cb == 0) {
                SDL_ReleaseGPUTransferBuffer(device.getDevice(), xfer);
                return;
            }
            final long copyPass = SDL_BeginGPUCopyPass(cb);
            if (copyPass == 0) {
                SDL_CancelGPUCommandBuffer(cb);
                SDL_ReleaseGPUTransferBuffer(device.getDevice(), xfer);
                return;
            }
            final SDL_GPUTextureRegion src = SDL_GPUTextureRegion.calloc(stack)
                .texture(tex).mip_level(0).x(x).y(y).z(0).w(1).h(1).d(1);
            final SDL_GPUTextureTransferInfo dst = SDL_GPUTextureTransferInfo.calloc(stack)
                .transfer_buffer(xfer).offset(0);
            SDL_DownloadFromGPUTexture(copyPass, src, dst);
            SDL_EndGPUCopyPass(copyPass);

            final long fence = Submits.submitAndAcquireFence(cb);
            if (fence == 0) {
                SDL_ReleaseGPUTransferBuffer(device.getDevice(), xfer);
                return;
            }
            final Slot slot = new Slot();
            slot.xfer = xfer;
            slot.fence = fence;
            slot.frameNumber = pending.frameNumber;
            slot.stats = pending;
            slots.add(slot);
        }
    }

    private void drainSignaled() {
        while (true) {
            final Slot s = slots.peek();
            if (s == null || !FenceWait.isSignaled(device, s.fence)) return;
            slots.poll();
            int pixel = -1;
            final long ptr = nSDL_MapGPUTransferBuffer(device.getDevice(), s.xfer, false);
            if (ptr != 0) {
                pixel = MemoryUtil.memGetInt(ptr);
                SDL_UnmapGPUTransferBuffer(device.getDevice(), s.xfer);
            }
            SDL_ReleaseGPUTransferBuffer(device.getDevice(), s.xfer);
            device.fenceReleaser().release(s.fence);
            if (pixel == -1) continue;
            if (pixel == 0xEEEEEEEE) {
                probeCopyFailures++;
                if (probeCopyFailures <= 20 || probeCopyFailures % 60 == 0) {
                    LOG.warn("black frame probe: frame {} download did not land (sentinel intact, #{}); err={}", s.frameNumber, probeCopyFailures, SDLError.SDL_GetError());
                }
                continue;
            }
            final long report = tracker.onPixel(s.frameNumber, isBlack(pixel));
            if (report != 0) {
                final Stats st = s.stats;
                LOG.warn("black frame probe: frame {} black center pixel (boundFbo={} swapchainPasses={} clearPasses={} inPassClears={} matClears={} fboPasses={} blits={} submits={} midSubmits={} empty={} stateApplies={} gpuWaitUs={} uboPushes={} uboSkips={} extUbo={} extSkips={} blockBreaks={} blockFlushes={} passEnds={})",
                    report, st.boundFbo, st.swapchainPasses, st.clearPasses, st.inPassClears, st.materializedClears,
                    st.fboPasses, st.blits, st.submits, st.midFrameSubmits, st.emptyFrames, st.stateApplies,
                    st.gpuWaitNanos / 1000L, st.uniformPushes, st.uniformPushSkips, st.externalUboPushes,
                    st.externalUboPushSkips, st.perFrameBlockPassBreaks, Arrays.toString(st.uniformBlockFlushes),
                    Arrays.toString(st.passEndCauses));
            }
        }
    }

    public void shutdown() {
        Slot s;
        while ((s = slots.poll()) != null) {
            if (s.fence != 0) device.fenceReleaser().release(s.fence);
            if (s.xfer != 0) SDL_ReleaseGPUTransferBuffer(device.getDevice(), s.xfer);
        }
    }
}
