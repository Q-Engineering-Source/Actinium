package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.glsm.texture.TextureStaging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the default client staging path of {@link RenderBackend}: the BGRA upload issued by
 * {@code commitTextureStaging}, the per-capacity reuse of the released blocks, the eviction order
 * once every cache slot is taken, the double-release guards, and the abandon/trim release paths.
 */
class TextureStagingTest {

    /** Cache depth of {@link RenderBackend}; the eviction tests fill it exactly to reach the reuse limit. */
    private static final int CACHE_SLOTS = 16;

    private final StagingRecordingBackend backend = new StagingRecordingBackend();

    /** Blocks this test still holds, so a failing assertion cannot leave native memory behind. */
    private final List<TextureStaging> outstanding = new ArrayList<>();

    @AfterEach
    void releaseOutstandingBlocks() {
        for (final TextureStaging staging : outstanding) {
            backend.abandonTextureStaging(staging);
        }
        outstanding.clear();
        backend.trimTextureStaging();
    }

    @Test
    void commitUploadsBgraPixelsAtRequestedRegion() {
        final TextureStaging staging = begin(2, 3, 5, 4, 2);
        assertTrue(staging.bgra(), "client staging holds BGRA byte order");
        assertEquals(2, staging.level());
        assertEquals(4 * 2 * 4, staging.buffer().capacity());

        final byte[] texels = bgraPattern(4, 2);
        for (int i = 0; i < texels.length; i++) {
            staging.buffer().put(i, texels[i]);
        }

        assertTrue(commit(staging));

        assertEquals(1, backend.uploads().size());
        final StagingRecordingBackend.Upload upload = backend.uploads().get(0);
        assertEquals(GL11.GL_TEXTURE_2D, upload.target());
        assertEquals(2, upload.level());
        assertEquals(3, upload.xoffset());
        assertEquals(5, upload.yoffset());
        assertEquals(4, upload.width());
        assertEquals(2, upload.height());
        assertEquals(GL12.GL_BGRA, upload.format());
        assertEquals(GL12.GL_UNSIGNED_INT_8_8_8_8_REV, upload.type());
        assertEquals(0, upload.position(), "the upload starts at the first staged byte");
        assertArrayEquals(texels, upload.pixels());
    }

    @Test
    void commitRecyclesTheBlockForTheNextSameSizeRequest() {
        final TextureStaging first = begin(0, 0, 0, 8, 8);
        final ByteBuffer block = first.buffer();
        block.put(0, (byte) 0x7F);
        assertTrue(commit(first));

        final TextureStaging second = begin(0, 0, 0, 8, 8);
        assertSame(block, second.buffer(), "the committed block returns to the cache");
        assertEquals(0, second.buffer().position(), "a recycled block is cleared before reuse");
        assertEquals(8 * 8 * 4, second.buffer().capacity());

        assertTrue(commit(second));
        assertEquals(2, backend.uploads().size());
    }

    @Test
    void recycledBlocksAreHandedOutForTheirOwnCapacityOnly() {
        final TextureStaging cached = begin(0, 0, 0, 8, 8);
        final ByteBuffer block = cached.buffer();
        abandon(cached);

        final TextureStaging smaller = begin(0, 0, 0, 4, 4);
        assertNotSame(block, smaller.buffer(), "a larger cached block is not handed out for a smaller region");
        assertEquals(4 * 4 * 4, smaller.buffer().capacity());

        assertSame(block, begin(0, 0, 0, 8, 8).buffer());
    }

    @Test
    void abandonReturnsTheBlockWithoutTouchingTheDriver() {
        final TextureStaging staging = begin(0, 0, 0, 8, 8);
        final ByteBuffer block = staging.buffer();
        abandon(staging);
        assertTrue(backend.uploads().isEmpty(), "an abandoned block never reaches texSubImage2D");

        assertSame(block, begin(0, 0, 0, 8, 8).buffer());
    }

    @Test
    void trimFreesEveryCachedBlock() {
        final TextureStaging staging = begin(0, 0, 0, 8, 8);
        final ByteBuffer block = staging.buffer();
        abandon(staging);

        backend.trimTextureStaging();

        final TextureStaging fresh = begin(0, 0, 0, 8, 8);
        assertNotSame(block, fresh.buffer(), "trim releases the cached block");
        assertEquals(8 * 8 * 4, fresh.buffer().capacity());
        assertTrue(backend.uploads().isEmpty());
    }

    @Test
    void fullCacheEvictsTheSmallestCachedBlock() {
        final List<ByteBuffer> cached = new ArrayList<>();
        for (int rows = 1; rows <= CACHE_SLOTS; rows++) {
            final TextureStaging staging = begin(0, 0, 0, 1, rows);
            cached.add(staging.buffer());
            abandon(staging);
        }

        final TextureStaging large = begin(0, 0, 0, 1, CACHE_SLOTS * 2);
        final ByteBuffer largeBlock = large.buffer();
        abandon(large);

        final TextureStaging smallest = begin(0, 0, 0, 1, 1);
        assertNotSame(cached.get(0), smallest.buffer(), "the smallest cached block is evicted to make room");
        assertEquals(1 * 1 * 4, smallest.buffer().capacity());
        for (int rows = 2; rows <= CACHE_SLOTS; rows++) {
            assertSame(cached.get(rows - 1), begin(0, 0, 0, 1, rows).buffer());
        }
        assertSame(largeBlock, begin(0, 0, 0, 1, CACHE_SLOTS * 2).buffer());
    }

    @Test
    void incomingBlockSmallerThanEveryCachedBlockIsFreed() {
        final List<TextureStaging> live = new ArrayList<>();
        final List<ByteBuffer> cached = new ArrayList<>();
        for (int i = 0; i < CACHE_SLOTS; i++) {
            final TextureStaging staging = begin(0, 0, 0, 1, CACHE_SLOTS * 2);
            live.add(staging);
            cached.add(staging.buffer());
        }
        for (final TextureStaging staging : live) {
            abandon(staging);
        }

        final TextureStaging small = begin(0, 0, 0, 1, 1);
        final ByteBuffer smallBlock = small.buffer();
        abandon(small);

        final TextureStaging fresh = begin(0, 0, 0, 1, 1);
        assertNotSame(smallBlock, fresh.buffer(), "a cache full of larger blocks releases the incoming block");

        final Set<ByteBuffer> recovered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < CACHE_SLOTS; i++) {
            recovered.add(begin(0, 0, 0, 1, CACHE_SLOTS * 2).buffer());
        }
        assertEquals(identitySet(cached), recovered, "every larger cached block survives the release");
    }

    @Test
    void emptySlotIsFilledBeforeASmallerCachedBlockIsEvicted() {
        final TextureStaging small = begin(0, 0, 0, 1, 1);
        final ByteBuffer smallBlock = small.buffer();
        abandon(small);

        final TextureStaging large = begin(0, 0, 0, 1, CACHE_SLOTS * 2);
        final ByteBuffer largeBlock = large.buffer();
        abandon(large);

        assertSame(smallBlock, begin(0, 0, 0, 1, 1).buffer(), "a free slot keeps the smaller cached block alive");
        assertSame(largeBlock, begin(0, 0, 0, 1, CACHE_SLOTS * 2).buffer());
    }

    @Test
    void secondReleaseOfABlockFails() {
        final TextureStaging committed = begin(0, 0, 0, 8, 8);
        final ByteBuffer block = committed.buffer();
        assertTrue(commit(committed));
        assertEquals(1, backend.uploads().size());

        assertThrows(IllegalStateException.class, () -> backend.commitTextureStaging(committed));
        assertEquals(1, backend.uploads().size(), "a rejected release must not reach the driver");
        assertThrows(IllegalStateException.class, () -> backend.abandonTextureStaging(committed));

        final TextureStaging abandoned = begin(0, 0, 0, 8, 8);
        assertSame(block, abandoned.buffer(), "the doubled release must not queue a second copy");
        abandon(abandoned);
        assertThrows(IllegalStateException.class, () -> backend.commitTextureStaging(abandoned));
        assertEquals(1, backend.uploads().size());

        final TextureStaging reused = begin(0, 0, 0, 8, 8);
        assertSame(block, reused.buffer());
        final TextureStaging fresh = begin(0, 0, 0, 8, 8);
        assertNotSame(block, fresh.buffer(), "the rejected releases add no second cache slot");
    }

    @Test
    void commitUploadsFromTheStartOfTheBufferAfterRelativeWrites() {
        final TextureStaging staging = begin(1, 2, 3, 2, 2);
        final byte[] texels = bgraPattern(2, 2);
        staging.buffer().put(texels);
        assertEquals(texels.length, staging.buffer().position(), "the producer left the buffer at its end");

        assertTrue(commit(staging));

        final StagingRecordingBackend.Upload upload = backend.uploads().get(0);
        assertEquals(0, upload.position(), "the upload window covers the whole block");
        assertEquals(1, upload.level());
        assertEquals(2, upload.xoffset());
        assertEquals(3, upload.yoffset());
        assertEquals(2, upload.width());
        assertEquals(2, upload.height());
        assertArrayEquals(texels, upload.pixels());
    }

    @Test
    void blockIssuedByAnotherBackendIsRejected() {
        final StagingRecordingBackend other = new StagingRecordingBackend();
        final TextureStaging foreign = other.beginTextureStaging(0, 0, 0, 8, 8);
        try {
            assertThrows(IllegalArgumentException.class, () -> backend.commitTextureStaging(foreign));
            assertThrows(IllegalArgumentException.class, () -> backend.abandonTextureStaging(foreign));
            assertTrue(backend.uploads().isEmpty(), "the wrong backend must not upload the block");

            assertTrue(other.commitTextureStaging(foreign), "the issuing backend still owns the block");
            assertEquals(1, other.uploads().size());
        } finally {
            other.trimTextureStaging();
        }
    }

    private TextureStaging begin(int level, int x, int y, int width, int height) {
        final TextureStaging staging = backend.beginTextureStaging(level, x, y, width, height);
        outstanding.add(staging);
        return staging;
    }

    private boolean commit(TextureStaging staging) {
        outstanding.remove(staging);
        return backend.commitTextureStaging(staging);
    }

    private void abandon(TextureStaging staging) {
        outstanding.remove(staging);
        backend.abandonTextureStaging(staging);
    }

    /** Builds distinct BGRA texels so a byte reorder or an offset mistake shows up in the upload. */
    private static byte[] bgraPattern(int width, int height) {
        final byte[] texels = new byte[width * height * 4];
        for (int i = 0; i < texels.length; i += 4) {
            texels[i] = (byte) (i + 1);
            texels[i + 1] = (byte) (i + 2);
            texels[i + 2] = (byte) (i + 3);
            texels[i + 3] = (byte) (i + 4);
        }
        return texels;
    }

    private static Set<ByteBuffer> identitySet(List<ByteBuffer> blocks) {
        final Set<ByteBuffer> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(blocks);
        return set;
    }
}
