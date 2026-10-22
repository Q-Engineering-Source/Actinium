package net.coderbot.iris.debug;

import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import net.coderbot.iris.gl.texture.DepthBufferFormat;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.texture.pbr.PBRType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Aggregates shader-owned texture estimates at lifecycle boundaries for native-memory diagnosis. */
public final class ShaderMemoryDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumShaders");

    private static final AtomicLong liveRenderTargetSets = new AtomicLong();
    private static final AtomicLong liveRenderTargetObjects = new AtomicLong();
    private static final AtomicLong liveRenderTargetEstBytes = new AtomicLong();
    private static final AtomicLong renderTargetCreates = new AtomicLong();
    private static final AtomicLong renderTargetResizes = new AtomicLong();
    private static final AtomicLong renderTargetDestroys = new AtomicLong();

    private static final AtomicLong ownedProgramCount = new AtomicLong();
    private static final AtomicLong ownedProgramCreates = new AtomicLong();
    private static final AtomicLong ownedProgramDestroys = new AtomicLong();

    private static final AtomicLong liveShadowTargetSets = new AtomicLong();
    private static final AtomicLong liveShadowColorBuffers = new AtomicLong();
    private static final AtomicLong liveShadowEstBytes = new AtomicLong();
    private static final AtomicLong shadowTargetCreates = new AtomicLong();
    private static final AtomicLong shadowColorBufferCreates = new AtomicLong();
    private static final AtomicLong shadowTargetDestroys = new AtomicLong();

    private static final AtomicLong livePbrAtlases = new AtomicLong();
    private static final AtomicLong livePbrAtlasEstBytes = new AtomicLong();
    private static final AtomicLong pbrAtlasUploads = new AtomicLong();
    private static final AtomicLong pbrAtlasCloses = new AtomicLong();
    private static final AtomicLong pbrImageDecodes = new AtomicLong();
    private static final AtomicLong pbrImageMaxEstBytes = new AtomicLong();

    private ShaderMemoryDiagnostics() {
    }

    public static void ownedProgramCreated() {
        ownedProgramCount.incrementAndGet();
        ownedProgramCreates.incrementAndGet();
    }

    public static void ownedProgramDestroyed() {
        ownedProgramCount.decrementAndGet();
        ownedProgramDestroys.incrementAndGet();
    }

    public static long estimateColorTextureBytes(InternalTextureFormat format, int width, int height) {
        return (long) width * height * bytesPerPixel(format);
    }

    public static long estimateDepthTextureBytes(DepthBufferFormat format, int width, int height) {
        final int bytesPerPixel = switch (format) {
            case DEPTH, DEPTH16 -> 2;
            case DEPTH24 -> 3;
            case DEPTH32, DEPTH32F, DEPTH_STENCIL, DEPTH24_STENCIL8 -> 4;
            case DEPTH32F_STENCIL8 -> 8;
        };
        return (long) width * height * bytesPerPixel;
    }

    public static long estimateMipmappedRgba8Bytes(int width, int height, int maxMipLevel) {
        long totalBytes = 0L;
        int mipWidth = width;
        int mipHeight = height;
        for (int level = 0; level <= maxMipLevel; level++) {
            totalBytes += (long) mipWidth * mipHeight * 4L;
            mipWidth = Math.max(1, mipWidth / 2);
            mipHeight = Math.max(1, mipHeight / 2);
        }
        return totalBytes;
    }

    private static int bytesPerPixel(InternalTextureFormat format) {
        final int bitsPerPixel = switch (format) {
            case R8, R8_SNORM, R8I, R8UI, R3_G3_B2, RGBA2 -> 8;
            case RG8, RG8_SNORM, RG8I, RG8UI, RGBA4, RGB5_A1, RGB565 -> 16;
            case RGB8, RGB8_SNORM, RGB8I, RGB8UI -> 24;
            case RGBA, RGBA8, RGBA8_SNORM, RGBA8I, RGBA8UI, RGB10_A2, RGB10_A2UI,
                R11F_G11F_B10F, RGB9_E5 -> 32;
            case R16, R16_SNORM, R16F, R16I, R16UI -> 16;
            case RG16, RG16_SNORM, RG16F, RG16I, RG16UI -> 32;
            case RGB16, RGB16_SNORM, RGB16F, RGB16I, RGB16UI -> 48;
            case RGBA16, RGBA16_SNORM, RGBA16F, RGBA16I, RGBA16UI -> 64;
            case R32F, R32I, R32UI -> 32;
            case RG32F, RG32I, RG32UI -> 64;
            case RGB32F, RGB32I, RGB32UI -> 96;
            case RGBA32F, RGBA32I, RGBA32UI -> 128;
        };
        return bitsPerPixel / Byte.SIZE;
    }

    public static void renderTargetsCreated(int targetObjects, long estBytes) {
        liveRenderTargetSets.incrementAndGet();
        liveRenderTargetObjects.addAndGet(targetObjects);
        liveRenderTargetEstBytes.addAndGet(estBytes);
        renderTargetCreates.incrementAndGet();
        logBoundary("texture", () -> "event=create kind=renderTargets targetObjects=" + targetObjects
            + " estBytes=" + estBytes);
    }

    public static void renderTargetsResized(int targetObjects, long previousEstBytes, long newEstBytes) {
        liveRenderTargetEstBytes.addAndGet(newEstBytes - previousEstBytes);
        renderTargetResizes.incrementAndGet();
        logBoundary("texture", () -> "event=resize kind=renderTargets targetObjects=" + targetObjects
            + " previousEstBytes=" + previousEstBytes + " estBytes=" + newEstBytes);
    }

    public static void renderTargetsDestroyed(int targetObjects, long estBytes) {
        liveRenderTargetSets.decrementAndGet();
        liveRenderTargetObjects.addAndGet(-targetObjects);
        liveRenderTargetEstBytes.addAndGet(-estBytes);
        renderTargetDestroys.incrementAndGet();
        logBoundary("texture", () -> "event=destroy kind=renderTargets targetObjects=" + targetObjects
            + " estBytes=" + estBytes);
    }

    public static void shadowTargetsCreated(int allocatedColorBuffers, long estBytes, int resolution) {
        liveShadowTargetSets.incrementAndGet();
        liveShadowColorBuffers.addAndGet(allocatedColorBuffers);
        liveShadowEstBytes.addAndGet(estBytes);
        shadowTargetCreates.incrementAndGet();
        logBoundary("texture", () -> "event=create kind=shadowTargets colorBuffers=" + allocatedColorBuffers
            + " resolution=" + resolution + " estBytes=" + estBytes);
    }

    public static void shadowColorBufferCreated(long estBytes) {
        liveShadowColorBuffers.incrementAndGet();
        liveShadowEstBytes.addAndGet(estBytes);
        shadowColorBufferCreates.incrementAndGet();
    }

    public static void shadowTargetsDestroyed(int allocatedColorBuffers, long estBytes, int resolution) {
        liveShadowTargetSets.decrementAndGet();
        liveShadowColorBuffers.addAndGet(-allocatedColorBuffers);
        liveShadowEstBytes.addAndGet(-estBytes);
        shadowTargetDestroys.incrementAndGet();
        logBoundary("texture", () -> "event=destroy kind=shadowTargets colorBuffers=" + allocatedColorBuffers
            + " resolution=" + resolution + " estBytes=" + estBytes);
    }

    public static void pbrAtlasUploaded(PBRType type, long previousEstBytes, long estBytes) {
        if (previousEstBytes == 0L) {
            livePbrAtlases.incrementAndGet();
        }
        livePbrAtlasEstBytes.addAndGet(estBytes - previousEstBytes);
        pbrAtlasUploads.incrementAndGet();
        logBoundary("texture", () -> "event=upload kind=pbrAtlas type=" + type + " estBytes=" + estBytes);
    }

    public static void pbrAtlasClosed(PBRType type, long estBytes) {
        if (estBytes == 0L) {
            return;
        }
        livePbrAtlases.decrementAndGet();
        livePbrAtlasEstBytes.addAndGet(-estBytes);
        pbrAtlasCloses.incrementAndGet();
        logBoundary("texture", () -> "event=destroy kind=pbrAtlas type=" + type + " estBytes=" + estBytes);
    }

    public static void pbrImageDecoded(int width, int height) {
        pbrImageDecodes.incrementAndGet();
        pbrImageMaxEstBytes.accumulateAndGet((long) width * height * 4L, Math::max);
    }

    /** Returns current byte estimates and lifetime event counts for the existing one-second perf report. */
    public static String statsSummary() {
        if (!GLSMPerfDebug.isEnabled()) {
            return "";
        }

        return "[ActiniumMemory][shader] ownedPrograms=" + ownedProgramCount.get()
            + ",ownedProgramCreateCount=" + ownedProgramCreates.get()
            + ",ownedProgramDestroyCount=" + ownedProgramDestroys.get()
            + " [ActiniumMemory][texture] renderTargetSets=" + liveRenderTargetSets.get()
            + ",renderTargetObjects=" + liveRenderTargetObjects.get()
            + ",renderTargetEstBytes=" + liveRenderTargetEstBytes.get()
            + ",renderTargetCreateCount=" + renderTargetCreates.get()
            + ",renderTargetResizeCount=" + renderTargetResizes.get()
            + ",renderTargetDestroyCount=" + renderTargetDestroys.get()
            + ",shadowTargetSets=" + liveShadowTargetSets.get()
            + ",shadowColorBuffers=" + liveShadowColorBuffers.get()
            + ",shadowEstBytes=" + liveShadowEstBytes.get()
            + ",shadowCreateCount=" + shadowTargetCreates.get()
            + ",shadowColorBufferCreateCount=" + shadowColorBufferCreates.get()
            + ",shadowDestroyCount=" + shadowTargetDestroys.get()
            + ",pbrAtlases=" + livePbrAtlases.get()
            + ",pbrAtlasEstBytes=" + livePbrAtlasEstBytes.get()
            + ",pbrAtlasUploadCount=" + pbrAtlasUploads.get()
            + ",pbrAtlasCloseCount=" + pbrAtlasCloses.get()
            + ",pbrImageDecodeCount=" + pbrImageDecodes.get()
            + ",pbrImageMaxEstBytes=" + pbrImageMaxEstBytes.get();
    }

    public static void log(String scope, Supplier<String> details) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }
        LOGGER.info("[ActiniumMemory][{}] {}", scope, details.get());
    }

    private static void logBoundary(String scope, Supplier<String> details) {
        if (GLSMPerfDebug.isEnabled()) {
            LOGGER.info("[ActiniumMemory][{}] {} {}", scope, details.get(), currentTextureEstimates());
        }
    }

    private static String currentTextureEstimates() {
        return "liveRenderTargetEstBytes=" + liveRenderTargetEstBytes.get()
            + ",liveShadowEstBytes=" + liveShadowEstBytes.get()
            + ",livePbrAtlasEstBytes=" + livePbrAtlasEstBytes.get();
    }
}
