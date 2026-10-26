package com.dhj.actinium.texture.atlas;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.texture.mipmap.SpriteMipmapExtension;
import com.dhj.actinium.util.PrefetchLane;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * One atlas load's parallel work: sprite images decoded ahead of the main thread, mipmap levels built on
 * workers where safe.
 *
 * <p>Frames always load on the main thread in vanilla order - a custom loader reads its dependencies' frames
 * through {@code textureGetter}. Only level building is parallel, and sprites a loader may read
 * ({@link AtlasLoadPlan#deferredChains}) build theirs in {@link #prepare}, after the loop. Main-thread only,
 * except for the worker level building.
 */
public final class AtlasLoadSession {

    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    /** Decodes one resource's image, matching however the game itself decodes it. */
    @FunctionalInterface
    public interface Decoder {
        BufferedImage decode(InputStream stream) throws IOException;
    }

    record Decoded(ResourceLocation location, IResource resource, BufferedImage image) implements AutoCloseable {
        @Override
        public void close() {
            closeImage(this.image);
            closeResource(this.resource);
        }
    }

    private static volatile AtlasLoadSession active;

    private final IResourceManager manager;
    private final TextureMap map;
    private final Function<TextureAtlasSprite, ResourceLocation> locator;
    private final boolean decodeEnabled;
    private final boolean mipmapsEnabled;
    private final TextureAtlasSprite missingImage;

    private Set<String> eligibleDomains = Set.of();
    private PrefetchLane<TextureAtlasSprite, Decoded> decodes;
    private final Set<TextureAtlasSprite> seeded = Collections.newSetFromMap(new IdentityHashMap<>());
    private Decoder decoder;
    private TextureAtlasSprite pendingSprite;
    private BufferedImage pendingImage;
    private int substituted;

    private Set<String> deferredChains = Set.of();
    private final List<CompletableFuture<Void>> mipmaps = new ArrayList<>();
    private final Set<TextureAtlasSprite> prepared = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> expected = ConcurrentHashMap.newKeySet();
    private final Set<int[][]> uploaded = Collections.newSetFromMap(new IdentityHashMap<>());
    private int mipmapsOffloaded;
    private int batchedUploads;
    private int framesReloaded;

    private boolean closed;

    private AtlasLoadSession(TextureMap map, IResourceManager manager, TextureAtlasSprite missingImage,
        Function<TextureAtlasSprite, ResourceLocation> locator, boolean decodeEnabled, boolean mipmapsEnabled) {
        this.map = map;
        this.manager = manager;
        this.missingImage = missingImage;
        this.locator = locator;
        this.decodeEnabled = decodeEnabled;
        this.mipmapsEnabled = mipmapsEnabled;
        // The load builds the missing image's levels itself, before the registration loop.
        this.expected.add(missingImage.getIconName());
    }

    /**
     * Returns null when no parallel path is enabled, leaving the vanilla load untouched. The batched upload
     * needs the session's bookkeeping, so it is part of the gate.
     */
    public static AtlasLoadSession begin(TextureMap map, IResourceManager manager, TextureAtlasSprite missingImage,
        Function<TextureAtlasSprite, ResourceLocation> locator) {
        final boolean decodeEnabled = ActiniumRuntimeOptions.enableParallelAtlasDecode();
        final boolean mipmapsEnabled = ActiniumRuntimeOptions.enableParallelAtlasMipmaps();
        if (!decodeEnabled && !mipmapsEnabled && !ActiniumRuntimeOptions.enableBatchedAtlasUpload()) {
            return null;
        }

        final AtlasLoadSession session = new AtlasLoadSession(map, manager, missingImage, locator, decodeEnabled,
            mipmapsEnabled);
        active = session;
        return session;
    }

    /** The session of the atlas load in progress, for the decode hook that lives on the sprite class. */
    public static AtlasLoadSession active() {
        return active;
    }

    /** Hands the session the registry before the loop starts: seeds the decode lane and fixes the deferred chains. */
    public void beginRegistration(Map<String, TextureAtlasSprite> registered) {
        final AtlasLoadPlan plan = AtlasLoadPlan.of(registered);
        this.deferredChains = plan.deferredChains();
        beginDecodes(plan.decodeOrder());
    }

    /**
     * Decides where one sprite's level building goes, called once its frames are in place: deferred to
     * {@link #prepare} when a loader may read the sprite, a worker when safe, or the caller otherwise.
     *
     * @return true when the session took the work, so the caller must not build the levels itself
     */
    public boolean takeGeneration(TextureAtlasSprite sprite, int levels) {
        // Recorded either way: the sprite's mipmap hook treats a generation it never saw as a mod's own.
        this.prepared.add(sprite);
        this.expected.add(sprite.getIconName());

        if (this.deferredChains.contains(sprite.getIconName())) {
            return true;
        }
        return offloadMipmaps(sprite, levels);
    }

    /** Builds the deferred level chains and joins the offloaded ones, after the loop, on its thread. */
    public void prepare(List<TextureAtlasSprite> sprites, int levels) {
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == this.missingImage) {
                continue;
            }
            final boolean reloaded = sprite.getFrameCount() <= 0 && reloadFrames(sprite, levels);
            if (!reloaded && sprite.getFrameCount() <= 0) {
                // Vanilla throws reading frame 0 in the upload loop; name the mod bug here instead of hiding it.
                LOGGER.warn("Atlas sprite {} ({}) has no frame to upload: {}x{}, custom loader={}, dependencies={}",
                    sprite.getIconName(), sprite.getClass().getName(), sprite.getIconWidth(), sprite.getIconHeight(),
                    sprite.hasCustomLoader(this.manager, this.locator.apply(sprite)), sprite.getDependencies());
            }
            // A chain built while the frames were missing has to be rebuilt now that they are back.
            if (reloaded || this.deferredChains.contains(sprite.getIconName())) {
                sprite.generateMipmaps(levels);
            }
        }
        joinMipmaps();
        recordStrategies();
    }

    /**
     * Loads a sprite's frames again when something emptied them after the loop's own load ran.
     *
     * <p>{@code TextureAtlasSprite.loadSprite} clears the frame list before it records a size, so a mod that
     * calls it ahead of the loop - StellarCore's sprite prefetch does, for every registered sprite - leaves the
     * sprite sized but frameless. The loop then reaches it and loads the frames back, unless the sprite is one
     * the loop never visits, and vanilla reads frame 0 for it during the upload and throws. Reloading here
     * restores the frames; the caller still builds their level chain the way it does for every other sprite.
     *
     * @return true when the sprite has frames again, so the caller need not report it
     */
    private boolean reloadFrames(TextureAtlasSprite sprite, int levels) {
        final ResourceLocation location = this.locator.apply(sprite);
        if (location == null || sprite.hasCustomLoader(this.manager, location)) {
            return false;
        }

        IResource resource = null;
        try {
            resource = this.manager.getResource(location);
            sprite.loadSpriteFrames(resource, levels + 1);
            this.framesReloaded++;
            LOGGER.info("Atlas sprite {} had no frame left by an earlier load, reloaded {} frame(s) from {}",
                sprite.getIconName(), sprite.getFrameCount(), location);
            return sprite.getFrameCount() > 0;
        } catch (RuntimeException | IOException e) {
            // Vanilla reports a sprite whose resource cannot be read the same way and keeps it frameless.
            LOGGER.warn("Unable to reload the frames of atlas sprite {} from {}", sprite.getIconName(), location, e);
            return false;
        } finally {
            closeResource(resource);
        }
    }

    /** Whether a generation belongs to this load. By name: the asking hook may run on a worker thread. */
    public boolean expects(TextureAtlasSprite sprite) {
        return this.expected.contains(sprite.getIconName());
    }

    /** Reports each generated sprite's mode; a broken resource never reached the hook and is skipped. */
    private void recordStrategies() {
        for (TextureAtlasSprite sprite : this.prepared) {
            AtlasLoadStats.recordStrategy(((SpriteMipmapExtension) sprite).celeritas$generatedMipmapStrategy());
        }
    }

    private void beginDecodes(List<TextureAtlasSprite> sprites) {
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
            if (sprite == this.missingImage) {
                continue;
            }
            this.seeded.add(sprite);
            lane.add(sprite, decodeTask(sprite));
        }
        this.decodes = lane;
    }

    private static int lookahead() {
        return 32 * AngelicaWorkers.threads();
    }

    private Callable<Decoded> decodeTask(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.getClass() != TextureAtlasSprite.class) {
            return null;
        }

        final ResourceLocation candidate = this.locator.apply(sprite);
        if (candidate == null || !this.eligibleDomains.contains(candidate.getNamespace())) {
            return null;
        }
        return () -> decode(candidate);
    }

    private Decoded decode(ResourceLocation candidate) throws IOException {
        final IResource resource = this.manager.getResource(candidate);
        BufferedImage image = null;
        try {
            // IResource owns this stream and closes it when the prefetched result is consumed or discarded.
            image = this.decoder.decode(resource.getInputStream());
            return new Decoded(candidate, resource, image);
        } catch (Throwable t) {
            closeImage(image);
            closeResource(resource);
            throw t;
        }
    }

    /**
     * Hands the prefetched resource for a sprite to the main thread, or null when not prefetched, on location
     * mismatch, or when the lane gave up. Unseeded sprites (registered mid-loop) must not ask the lane - that
     * would read as an out-of-order consumer and desynchronise it.
     */
    public IResource takeResource(TextureAtlasSprite sprite, ResourceLocation location) {
        if (this.decodes == null || !this.seeded.contains(sprite)) {
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
        this.pendingImage = decoded.image();
        this.substituted++;
        return decoded.resource();
    }

    /**
     * Consumes the decoded image for the sprite currently loading its frames. Matches the sprite, not the
     * stream: resources hand out a fresh stream per call.
     */
    public BufferedImage takeImage(TextureAtlasSprite sprite) {
        if (this.pendingImage == null || sprite != this.pendingSprite) {
            return null;
        }

        return this.pendingImage;
    }

    /** Releases the decoded image after vanilla has copied its pixels into the sprite's frame arrays. */
    public void finishImageLoad(TextureAtlasSprite sprite) {
        if (sprite != this.pendingSprite) {
            return;
        }

        closeImage(this.pendingImage);
        this.pendingSprite = null;
        this.pendingImage = null;
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

    /** Hands level building to a worker; until {@link #joinMipmaps} no other thread may read the sprite's frames. */
    private boolean offloadMipmaps(TextureAtlasSprite sprite, int levels) {
        if (!this.mipmapsEnabled || !MipmapSafety.isSafe(sprite.getClass())) {
            return false;
        }
        this.mipmapsOffloaded++;
        this.mipmaps.add(AngelicaWorkers.run(() -> sprite.generateMipmaps(levels)));
        return true;
    }

    /** Waits for every offloaded mipmap, rethrowing the first failure. */
    public void joinMipmaps() {
        awaitMipmaps(this.mipmaps);
    }

    /** Waits for all submitted chains even after one fails, so close can never leave a worker writing atlas pixels. */
    static void awaitMipmaps(List<CompletableFuture<Void>> mipmaps) {
        Throwable firstFailure = null;
        boolean interrupted = false;

        for (CompletableFuture<Void> future : mipmaps) {
            boolean finished = false;
            while (!finished) {
                try {
                    future.get();
                    finished = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (firstFailure == null) {
                        firstFailure = e;
                    } else if (firstFailure != e) {
                        firstFailure.addSuppressed(e);
                    }
                } catch (ExecutionException | CancellationException e) {
                    finished = true;
                    final Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    if (firstFailure == null) {
                        firstFailure = cause;
                    } else if (firstFailure != cause) {
                        firstFailure.addSuppressed(cause);
                    }
                }
            }
        }
        mipmaps.clear();

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (firstFailure != null) {
            throw new IllegalStateException(interrupted
                ? "Interrupted while generating atlas mipmaps"
                : "Parallel atlas mipmap generation failed", firstFailure);
        }
    }

    /** Records a sprite the batched upload wrote, keyed by frame array - the only identity at the hooked upload call. */
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

        final PrefetchLane<TextureAtlasSprite, Decoded> lane = this.decodes;
        final Throwable failure = AtlasLoadLifecycle.runCleanup(null,
            () -> { if (lane != null) lane.close(); },
            () -> { if (lane != null) lane.awaitQuiescence(); },
            this::releasePending,
            this::joinMipmaps,
            () -> {
                if (active == this) {
                    active = null;
                }
            },
            () -> {
                // Own numbers go into the build report, which the atlas map logs once the load is over.
                AtlasLoadStats.recordDecode(lane == null ? 0 : lane.submitted(), this.substituted,
                    lane == null ? 0 : lane.failures(), lane == null ? 0 : lane.cancellations(),
                    lane != null && lane.isDesynced());
                if (lane != null && lane.failures() > 0) {
                    LOGGER.warn("Parallel atlas decode failed for {} task(s); affected sprites loaded on the main thread",
                        lane.failures(), lane.firstFailure());
                }
                AtlasLoadStats.recordMipmapsOffloaded(this.mipmapsOffloaded);
                AtlasLoadStats.recordBatchedUploads(this.batchedUploads);
                AtlasLoadStats.recordFramesReloaded(this.framesReloaded);
                AtlasLoadStats.recordPrepared(this.prepared.size());
            });
        if (failure != null) {
            AtlasLoadLifecycle.rethrow(failure);
        }
    }

    private void releasePending() {
        if (this.pendingImage != null) {
            closeImage(this.pendingImage);
        }
        this.pendingSprite = null;
        this.pendingImage = null;
    }

    static void dispose(Decoded decoded) {
        decoded.close();
    }

    private static void closeImage(BufferedImage image) {
        if (image == null) {
            return;
        }
        if (image instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.warn("Failed to close a prefetched atlas image", e);
            }
        } else {
            image.flush();
        }
    }

    private static void closeResource(IResource resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (IOException e) {
            LOGGER.warn("Failed to close a prefetched atlas resource", e);
        }
    }
}
