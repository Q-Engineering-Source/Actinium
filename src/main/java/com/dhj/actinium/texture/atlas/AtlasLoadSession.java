package com.dhj.actinium.texture.atlas;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.util.PrefetchLane;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * One atlas load's parallel work: sprite images are decoded ahead of the main thread, and the mipmaps of the
 * sprites it has just decoded are generated on worker threads.
 *
 * <p>Vanilla decodes a sprite and builds its mipmaps inside the same loop iteration, right before uploading
 * it, so the two parallel phases are driven from that loop: the decode lane is seeded in the stitcher's
 * consumption order, and every sprite the main thread decodes is handed on for parallel mipmap generation.
 * The results are joined before the atlas assembler reads the whole atlas, which is what makes the mipmap
 * work overlap with the remaining decodes.
 *
 * <p>A session is created for the duration of one {@code TextureMap.loadTextureAtlas} call, and is
 * main-thread only.
 */
public final class AtlasLoadSession {

    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    /** Decodes one resource's image, matching however the game itself decodes it. */
    @FunctionalInterface
    public interface Decoder {
        BufferedImage decode(InputStream stream) throws IOException;
    }

    /** Builds one sprite's frames and mipmaps exactly as the vanilla atlas load does. */
    @FunctionalInterface
    public interface SpriteGenerator {
        boolean generate(TextureAtlasSprite sprite);
    }

    private record Decoded(ResourceLocation location, IResource resource, BufferedImage image) {}

    private static volatile AtlasLoadSession active;

    private final IResourceManager manager;
    private final TextureMap map;
    private final boolean decodeEnabled;
    private final boolean mipmapsEnabled;

    private Set<String> eligibleDomains = Set.of();
    private PrefetchLane<TextureAtlasSprite, Decoded> decodes;
    private Decoder decoder;
    private TextureAtlasSprite pendingSprite;
    private InputStream pendingStream;
    private BufferedImage pendingImage;
    private int substituted;

    private final List<CompletableFuture<Void>> mipmaps = new ArrayList<>();
    private final Map<TextureAtlasSprite, Boolean> generated = new IdentityHashMap<>();
    private final Set<int[][]> uploaded = Collections.newSetFromMap(new IdentityHashMap<>());
    private int mipmapsOffloaded;
    private int batchedUploads;

    private boolean closed;

    private AtlasLoadSession(TextureMap map, IResourceManager manager, boolean decodeEnabled, boolean mipmapsEnabled) {
        this.map = map;
        this.manager = manager;
        this.decodeEnabled = decodeEnabled;
        this.mipmapsEnabled = mipmapsEnabled;
    }

    /**
     * Returns null when the options ask for no parallel work at all, which leaves the vanilla path untouched.
     * The batched upload needs the session for its "already written" bookkeeping, so it is part of the gate.
     */
    public static AtlasLoadSession begin(TextureMap map, IResourceManager manager) {
        final boolean decodeEnabled = ActiniumRuntimeOptions.enableParallelAtlasDecode();
        final boolean mipmapsEnabled = ActiniumRuntimeOptions.enableParallelAtlasMipmaps();
        if (!decodeEnabled && !mipmapsEnabled && !ActiniumRuntimeOptions.enableBatchedAtlasUpload()) {
            return null;
        }

        final AtlasLoadSession session = new AtlasLoadSession(map, manager, decodeEnabled, mipmapsEnabled);
        active = session;
        return session;
    }

    /** The session of the atlas load in progress, for the decode hook that lives on the sprite class. */
    public static AtlasLoadSession active() {
        return active;
    }

    /**
     * Seeds the decode lane, runs the vanilla per-sprite generation for every stitched sprite so its mipmaps
     * can overlap with the remaining decodes, and waits for both before the caller touches the frames.
     */
    public void prepare(List<TextureAtlasSprite> sprites, TextureAtlasSprite missingImage,
        Function<TextureAtlasSprite, ResourceLocation> locator, SpriteGenerator generator) {
        beginDecodes(sprites, missingImage, locator);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == missingImage) {
                continue;
            }
            this.generated.put(sprite, generator.generate(sprite));
        }
        joinMipmaps();
    }

    private void beginDecodes(List<TextureAtlasSprite> sprites, TextureAtlasSprite missingImage,
        Function<TextureAtlasSprite, ResourceLocation> locator) {
        if (!this.decodeEnabled || this.decodes != null) {
            return;
        }

        if (this.manager.getClass() != SimpleReloadableResourceManager.class) {
            LOGGER.warn("Parallel atlas decode disabled: resource manager {} loads domains on its own", this.manager.getClass().getName());
            return;
        }

        this.eligibleDomains = AtlasPackPolicy.eligibleDomains((SimpleReloadableResourceManager) this.manager);
        if (this.eligibleDomains.isEmpty()) {
            return;
        }

        final PrefetchLane<TextureAtlasSprite, Decoded> lane = new PrefetchLane<>(AngelicaWorkers::submit, lookahead(),
            AtlasLoadSession::dispose);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == missingImage) {
                continue;
            }
            lane.add(sprite, decodeTask(sprite, locator));
        }
        this.decodes = lane;
    }

    private static int lookahead() {
        return 32 * AngelicaWorkers.threads();
    }

    private Callable<Decoded> decodeTask(TextureAtlasSprite sprite,
        Function<TextureAtlasSprite, ResourceLocation> locator) {
        if (sprite == null || sprite.getClass() != TextureAtlasSprite.class) {
            return null;
        }

        final ResourceLocation candidate = locator.apply(sprite);
        if (candidate == null || !this.eligibleDomains.contains(candidate.getNamespace())) {
            return null;
        }
        return () -> decode(candidate);
    }

    private Decoded decode(ResourceLocation candidate) throws IOException {
        IResource resource = null;
        BufferedImage image = null;
        try {
            resource = this.manager.getResource(candidate);
            image = this.decoder.decode(resource.getInputStream());
            return new Decoded(candidate, resource, image);
        } catch (Throwable t) {
            closeImage(image);
            if (resource != null) {
                IOUtils.closeQuietly(resource.getInputStream());
            }
            throw t;
        }
    }

    /**
     * Hands the prefetched resource for a sprite over to the main thread, or null when this sprite was not
     * prefetched, when the location does not match, or when the lane gave up.
     */
    public IResource takeResource(TextureAtlasSprite sprite, ResourceLocation location) {
        if (this.decodes == null) {
            return null;
        }

        releasePending();
        final Decoded decoded = this.decodes.take(sprite);
        if (decoded == null) {
            return null;
        }
        if (!location.equals(decoded.location())) {
            dispose(decoded);
            return null;
        }

        this.pendingSprite = sprite;
        this.pendingStream = decoded.resource().getInputStream();
        this.pendingImage = decoded.image();
        this.substituted++;
        return decoded.resource();
    }

    /**
     * Consumes the image the worker decoded for the sprite currently loading its frames. Matching the sprite
     * rather than the stream is deliberate: resource implementations hand out a fresh stream on every call,
     * so the stream the decode hook sees is never the one the prefetch stored.
     */
    public BufferedImage takeImage(TextureAtlasSprite sprite, InputStream stream) {
        if (this.pendingImage == null || sprite != this.pendingSprite) {
            return null;
        }

        final BufferedImage image = this.pendingImage;
        this.pendingSprite = null;
        this.pendingStream = null;
        this.pendingImage = null;
        return image;
    }

    /** True while the lane still needs the decoding function the main thread used for its first sprite. */
    public boolean wantsDecoder() {
        return this.decodes != null && this.decoder == null;
    }

    /** Registers the main thread's own decode as the function the lane decodes with, and starts the lane. */
    public void onMainDecode(Decoder decoder) {
        if (this.decodes == null || this.decoder != null) {
            return;
        }
        this.decoder = decoder;
        this.decodes.start();
    }

    /**
     * Offloads one sprite's mipmap generation, or returns false to leave it to the calling thread. The sprite
     * is queried again for its frames only after {@link #joinMipmaps()}.
     */
    public boolean offloadMipmaps(TextureAtlasSprite sprite, int levels) {
        if (!this.mipmapsEnabled || !MipmapSafety.isSafe(sprite.getClass())) {
            return false;
        }
        this.mipmapsOffloaded++;
        this.mipmaps.add(AngelicaWorkers.run(() -> sprite.generateMipmaps(levels)));
        return true;
    }

    /** Waits for every offloaded mipmap, falling back to the calling thread when one of them failed. */
    public void joinMipmaps() {
        final int count = this.mipmaps.size();
        for (int i = 0; i < count; i++) {
            try {
                this.mipmaps.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while generating atlas mipmaps", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Parallel atlas mipmap generation failed", e.getCause());
            }
        }
        this.mipmaps.clear();
    }

    /** The result of the vanilla generation for a sprite, or null when this session never ran it. */
    public Boolean preparedResult(TextureAtlasSprite sprite) {
        return this.generated.get(sprite);
    }

    /**
     * Records a sprite the batched upload already wrote. The frame array is the key rather than the sprite,
     * because the vanilla upload loop is hooked at the texture upload call, where the frame data is the only
     * thing that identifies the sprite.
     */
    public void markUploaded(TextureAtlasSprite sprite) {
        final int[][] frame = sprite.getFrameCount() > 0 ? sprite.getFrameTextureData(0) : null;
        if (frame != null) {
            this.uploaded.add(frame);
            this.batchedUploads++;
        }
    }

    /** One-shot: true when the batched upload already wrote the pixels this frame data belongs to. */
    public boolean takeUploaded(int[][] frame) {
        return this.uploaded.remove(frame);
    }

    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        active = null;

        final PrefetchLane<TextureAtlasSprite, Decoded> lane = this.decodes;
        if (lane != null) {
            lane.close();
            lane.awaitQuiescence();
        }
        releasePending();
        joinMipmaps();

        // The parallel path's own numbers go into the build report, which the atlas map logs once the load
        // is over and the mip level count and gutter are final.
        AtlasLoadStats.recordDecode(lane == null ? 0 : lane.submitted(), this.substituted,
            lane == null ? 0 : lane.failures(), lane != null && lane.isDesynced());
        AtlasLoadStats.recordMipmapsOffloaded(this.mipmapsOffloaded);
        AtlasLoadStats.recordBatchedUploads(this.batchedUploads);
        AtlasLoadStats.recordPrepared(this.generated.size());
    }

    private void releasePending() {
        if (this.pendingImage != null) {
            closeImage(this.pendingImage);
        }
        this.pendingSprite = null;
        this.pendingStream = null;
        this.pendingImage = null;
    }

    private static void dispose(Decoded decoded) {
        closeImage(decoded.image());
        IOUtils.closeQuietly(decoded.resource().getInputStream());
    }

    private static void closeImage(BufferedImage image) {
        if (image instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.warn("Failed to close a prefetched atlas image", e);
            }
        }
    }
}
