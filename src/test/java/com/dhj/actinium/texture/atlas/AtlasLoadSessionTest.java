package com.dhj.actinium.texture.atlas;

import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import com.dhj.actinium.texture.mipmap.SpriteMipmapExtension;
import com.dhj.actinium.util.PrefetchLane;
import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session knows which sprites a load generates mipmaps for (the build report's counts) and where each
 * level chain may be built. Driven the way {@code TextureMap} drives it: handed the registry, then every
 * sprite the loop loads frames for.
 */
class AtlasLoadSessionTest {

    private static final String DECODE_PROPERTY = "actinium.parallelAtlasDecode";
    private static final String MIPMAPS_PROPERTY = "actinium.parallelAtlasMipmaps";
    private static final String BATCHED_UPLOAD_PROPERTY = "actinium.batchedAtlasUpload";

    private static final int LEVELS = 4;

    /** A resource manager with no packs at all, which the decode lane is kept out of by the options. */
    private static final IResourceManager MANAGER = new IResourceManager() {
        @Override
        public Set<String> getResourceDomains() {
            return Set.of();
        }

        @Override
        public IResource getResource(ResourceLocation location) {
            throw new UnsupportedOperationException("this test's resource manager has no packs");
        }

        @Override
        public List<IResource> getAllResources(ResourceLocation location) {
            throw new UnsupportedOperationException("this test's resource manager has no packs");
        }
    };

    /** The location a sprite's image would live at; nothing reads it while the decode lane is off. */
    private static final Function<TextureAtlasSprite, ResourceLocation> LOCATOR =
        sprite -> new ResourceLocation("minecraft", sprite.getIconName().replace(':', '/') + ".png");

    private final String originalDecode = System.getProperty(DECODE_PROPERTY);
    private final String originalMipmaps = System.getProperty(MIPMAPS_PROPERTY);
    private final String originalBatchedUpload = System.getProperty(BATCHED_UPLOAD_PROPERTY);

    @BeforeEach
    void enableTheParallelMipmapPathTheSessionNeeds() {
        // A session exists as soon as one of the three parallel paths is on. The decode lane stays off so no
        // worker is involved in loading the frames.
        System.setProperty(DECODE_PROPERTY, "false");
        System.setProperty(MIPMAPS_PROPERTY, "true");
        System.setProperty(BATCHED_UPLOAD_PROPERTY, "false");
    }

    @AfterEach
    void restoreTheOptionsAndTheReport() {
        restoreProperty(DECODE_PROPERTY, this.originalDecode);
        restoreProperty(MIPMAPS_PROPERTY, this.originalMipmaps);
        restoreProperty(BATCHED_UPLOAD_PROPERTY, this.originalBatchedUpload);
        AtlasLoadStats.snapshot("test", 0, 1, TextureFilterMode.NONE, 1, false, 0L);
    }

    @Test
    void countsEverySpriteItGeneratedOnceAndKeepsForeignSpritesOutOfTheReport() {
        AtlasLoadStats.begin();

        final TextureMap map = new TextureMap("textures/blocks");
        final PreparedSprite missing = new PreparedSprite("missingno", MipmapStrategy.MEAN);
        final PreparedSprite leaves = new PreparedSprite("minecraft:blocks/leaves_oak", MipmapStrategy.CUTOUT);
        final PreparedSprite stone = new PreparedSprite("minecraft:blocks/stone", MipmapStrategy.MEAN);
        final PreparedSprite glass = new PreparedSprite("minecraft:blocks/glass", MipmapStrategy.STRICT_CUTOUT);
        final List<PreparedSprite> sprites = List.of(leaves, stone, glass);

        final AtlasLoadSession session = AtlasLoadSession.begin(map, MANAGER, missing, LOCATOR);
        assertNotNull(session, "the parallel mipmap option is on, so the load has a session");
        try {
            assertSame(session, AtlasLoadSession.active());

            // The registration loop asks the session about every sprite it loads frames for; the load built
            // the missing image before the loop.
            session.beginRegistration(registryOf(leaves, stone, glass));
            for (PreparedSprite sprite : sprites) {
                assertFalse(session.takeGeneration(sprite, LEVELS),
                    "a modded sprite class builds its levels on the main thread, where the loop leaves them");
            }
            session.prepare(List.of(leaves, stone, glass, missing), LEVELS);
            AtlasLoadStats.recordSprites(4);

            assertTrue(session.expects(leaves));
            assertTrue(session.expects(missing), "the load generates the missing image itself, so its call is not foreign");
            assertFalse(session.expects(new PreparedSprite("minecraft:blocks/emerald", MipmapStrategy.MEAN)),
                "a sprite this atlas did not register generated its levels for itself, and is no sprite of this load");
        } finally {
            session.close();
        }

        assertNull(AtlasLoadSession.active(), "no load is in progress, so nothing is attributed to one");

        final AtlasLoadStats.Values values = AtlasLoadStats.snapshot("textures/blocks", 4, 16,
            TextureFilterMode.RGSS, 1, false, 1L);

        assertEquals(4, values.sprites());
        assertEquals(3, values.prepared(), "the missing image is generated by the load, not by the session");
        assertEquals(Map.of(MipmapStrategy.MEAN, 1, MipmapStrategy.CUTOUT, 1, MipmapStrategy.STRICT_CUTOUT, 1),
            values.strategies());
        assertEquals(values.prepared(), countedSprites(values), "Sigma strategies = prepared: one count per sprite, and no foreign call in it");
        assertEquals(values.sprites() - 1, countedSprites(values), "the missing image is what makes sprites one larger than the counts");
        assertEquals(0, values.foreignMipmapCalls(), "nothing generated outside the load's own sprites here");
    }

    @Test
    void leavesOutASpriteWhoseGenerationNeverResolvedAMode() {
        AtlasLoadStats.begin();

        final TextureMap map = new TextureMap("textures/items");
        final PreparedSprite missing = new PreparedSprite("missingno", null);
        final PreparedSprite apple = new PreparedSprite("minecraft:items/apple", MipmapStrategy.MEAN);
        final PreparedSprite broken = new PreparedSprite("minecraft:items/broken", null);

        final AtlasLoadSession session = AtlasLoadSession.begin(map, MANAGER, missing, LOCATOR);
        try {
            session.beginRegistration(registryOf(apple, broken));
            session.takeGeneration(apple, LEVELS);
            session.takeGeneration(broken, LEVELS);
            session.prepare(List.of(apple, broken), LEVELS);
        } finally {
            session.close();
        }

        final AtlasLoadStats.Values values = AtlasLoadStats.snapshot("textures/items", 4, 16,
            TextureFilterMode.NONE, 1, false, 1L);

        assertEquals(2, values.prepared(), "the session ran the generation for both");
        assertEquals(Map.of(MipmapStrategy.MEAN, 1), values.strategies(),
            "a sprite the load skipped before its levels were built has no mode to report");
        assertEquals(0, values.foreignMipmapCalls());
    }

    /** A sprite a loader can read keeps its chain off the loop; it is built once, in the pass after the loop. */
    @Test
    void buildsOnlyTheDeferredLevelChainsInThePassAfterTheLoop() {
        AtlasLoadStats.begin();

        final TextureMap map = new TextureMap("textures/blocks");
        final PreparedSprite missing = new PreparedSprite("missingno", null);
        final LevelCountingSprite base = new LevelCountingSprite("minecraft:blocks/base");
        final LevelCountingSprite plain = new LevelCountingSprite("minecraft:blocks/plain");
        final LevelCountingSprite derived = new DerivedSprite("minecraft:blocks/derived", base);

        final AtlasLoadSession session = AtlasLoadSession.begin(map, MANAGER, missing, LOCATOR);
        try {
            session.beginRegistration(registryOf(base, plain, derived));

            assertTrue(session.takeGeneration(base, LEVELS),
                "a dependency the loop's loader reads keeps its levels for the pass after the loop");
            assertFalse(session.takeGeneration(plain, LEVELS),
                "a sprite no loader reads is built where vanilla builds it");
            assertFalse(session.takeGeneration(derived, LEVELS),
                "the loader's own sprite is built where vanilla builds it");
            assertEquals(0, base.levelsBuilt, "nothing built the dependency's levels while the loop ran");

            session.prepare(List.of(base, plain, derived), LEVELS);

            assertEquals(1, base.levelsBuilt, "the deferred chain is built exactly once, in the pass after the loop");
            assertEquals(0, plain.levelsBuilt, "the pass must not build a chain the registration loop already built");
            assertEquals(0, derived.levelsBuilt, "nor the one the loader's own sprite was given");
        } finally {
            session.close();
        }

        final AtlasLoadStats.Values values = AtlasLoadStats.snapshot("textures/blocks", 4, 16,
            TextureFilterMode.NONE, 1, false, 1L);

        assertEquals(3, values.prepared(), "all three sprites were the load's own generation");
        assertEquals(0, values.mipmapsOffloaded(),
            "no sprite of this registry may go to a worker: one a loader reads, one built inline, one a subclass");
    }

    /** A registered sprite no loader reads goes to a worker, and only that sprite. */
    @Test
    void handsASpriteNoLoaderReadsToAWorker() {
        AtlasLoadStats.begin();

        final TextureMap map = new TextureMap("textures/blocks");
        final TextureAtlasSprite plain = map.registerSprite(new ResourceLocation("minecraft:blocks/plain"));
        final PreparedSprite missing = new PreparedSprite("missingno", null);
        final LevelCountingSprite base = new LevelCountingSprite("minecraft:blocks/base");
        final DerivedSprite derived = new DerivedSprite("minecraft:blocks/derived", base);

        final AtlasLoadSession session = AtlasLoadSession.begin(map, MANAGER, missing, LOCATOR);
        try {
            session.beginRegistration(registryOf(plain, base, derived));

            assertTrue(session.takeGeneration(plain, LEVELS),
                "nothing reads this sprite's frames, so the session builds its levels on a worker");
            assertTrue(session.takeGeneration(base, LEVELS),
                "the loader's dependency keeps its levels for the main thread instead");
        } finally {
            session.close();
        }

        final AtlasLoadStats.Values values = AtlasLoadStats.snapshot("textures/blocks", 4, 16,
            TextureFilterMode.NONE, 1, false, 1L);

        assertEquals(2, values.prepared());
        assertEquals(1, values.mipmapsOffloaded(), "exactly the sprite no loader reads went to a worker");
        assertTrue(values.parallelMipmaps(), "and that is what the build report calls a parallel load");
    }

    @Test
    void laneDisposalClosesItsDecodedImageAndResourceExactlyOnce() {
        final TrackingResource resource = new TrackingResource();
        final TrackingStream stream = (TrackingStream) resource.getInputStream();
        final TrackingImage image = new TrackingImage();
        final AtlasLoadSession.Decoded decoded = new AtlasLoadSession.Decoded(
            new ResourceLocation("minecraft", "textures/blocks/stone.png"), resource, image);
        final PrefetchLane<String, AtlasLoadSession.Decoded> lane = new PrefetchLane<>(
            task -> CompletableFuture.completedFuture(task.get()), 1, AtlasLoadSession::dispose);
        lane.add("stone", () -> decoded);

        lane.start();
        lane.close();
        lane.awaitQuiescence();
        lane.close();

        assertEquals(1, image.closeCount);
        assertEquals(1, resource.closeCount);
        assertEquals(1, resource.inputStreamCount,
            "disposal does not open another stream on the resource");
        assertEquals(1, stream.closeCount, "closing the resource releases the stream it owns");
    }

    @Test
    void takingLaneResultTransfersCleanupToTheConsumer() {
        final TrackingResource resource = new TrackingResource();
        final TrackingStream stream = (TrackingStream) resource.getInputStream();
        final TrackingImage image = new TrackingImage();
        final AtlasLoadSession.Decoded decoded = new AtlasLoadSession.Decoded(
            new ResourceLocation("minecraft", "textures/blocks/stone.png"), resource, image);
        final PrefetchLane<String, AtlasLoadSession.Decoded> lane = new PrefetchLane<>(
            task -> CompletableFuture.completedFuture(task.get()), 1, AtlasLoadSession::dispose);
        lane.add("stone", () -> decoded);

        lane.start();
        assertSame(decoded, lane.take("stone"));
        lane.close();
        lane.awaitQuiescence();

        assertEquals(0, image.closeCount);
        assertEquals(0, resource.closeCount,
            "a consumed result leaves the resource and image owned by the main-thread loader");
        assertEquals(0, stream.closeCount,
            "the lane does not close a stream transferred with its resource");

        decoded.close();
        assertEquals(1, image.closeCount);
        assertEquals(1, resource.closeCount);
        assertEquals(1, stream.closeCount);
    }

    private static int countedSprites(AtlasLoadStats.Values values) {
        return values.strategies().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The registry the registration loop walks, keyed the way {@code TextureMap} keys it. */
    private static Map<String, TextureAtlasSprite> registryOf(TextureAtlasSprite... sprites) {
        final Map<String, TextureAtlasSprite> registered = new LinkedHashMap<>();
        for (TextureAtlasSprite sprite : sprites) {
            registered.put(sprite.getIconName(), sprite);
        }
        return registered;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static final class TrackingImage extends BufferedImage implements AutoCloseable {
        private int closeCount;

        private TrackingImage() {
            super(1, 1, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void close() {
            this.closeCount++;
            flush();
        }
    }

    private static final class TrackingResource implements IResource {
        private int closeCount;
        private int inputStreamCount;
        private final List<TrackingStream> streams = new ArrayList<>();

        @Override
        public ResourceLocation getResourceLocation() {
            return new ResourceLocation("minecraft", "textures/blocks/stone.png");
        }

        @Override
        public InputStream getInputStream() {
            this.inputStreamCount++;
            final TrackingStream stream = new TrackingStream();
            this.streams.add(stream);
            return stream;
        }

        @Override
        public boolean hasMetadata() {
            return false;
        }

        @Override
        public <T extends IMetadataSection> T getMetadata(String sectionName) {
            return null;
        }

        @Override
        public String getResourcePackName() {
            return "tracking";
        }

        @Override
        public void close() {
            this.closeCount++;
            this.streams.forEach(TrackingStream::close);
        }
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private int closeCount;

        private TrackingStream() {
            super(new byte[0]);
        }

        @Override
        public void close() {
            this.closeCount++;
        }
    }

    /** A sprite whose generation mode is handed in - the answering mixin is not applied outside the game. */
    private static final class PreparedSprite extends TextureAtlasSprite implements SpriteMipmapExtension {

        private final MipmapStrategy generatedMipmapStrategy;

        private PreparedSprite(String name, MipmapStrategy generatedMipmapStrategy) {
            super(name);
            this.generatedMipmapStrategy = generatedMipmapStrategy;
        }

        @Override
        public MipmapStrategy celeritas$generatedMipmapStrategy() {
            return this.generatedMipmapStrategy;
        }
    }

    /** A sprite that counts the level chains built for it, so a test can tell where they were built. */
    private static class LevelCountingSprite extends TextureAtlasSprite implements SpriteMipmapExtension {

        private int levelsBuilt;

        private LevelCountingSprite(String name) {
            super(name);
        }

        @Override
        public void generateMipmaps(int level) {
            this.levelsBuilt++;
        }

        @Override
        public MipmapStrategy celeritas$generatedMipmapStrategy() {
            return null;
        }
    }

    /** The shape a mod's sprite loader takes: it claims the sprite and names the frames it reads. */
    private static final class DerivedSprite extends LevelCountingSprite {

        private final ResourceLocation dependency;

        private DerivedSprite(String name, TextureAtlasSprite dependency) {
            super(name);
            this.dependency = new ResourceLocation(dependency.getIconName());
        }

        @Override
        public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) {
            return true;
        }

        @Override
        public Collection<ResourceLocation> getDependencies() {
            return List.of(this.dependency);
        }
    }
}
