package com.dhj.actinium.texture.atlas;

import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The atlas build report is what a resource reload answers "did the parallel decode, the parallel mipmaps
 * and the batched upload actually run, and what did each phase cost" with. {@link AtlasLoadStats#finish}
 * needs the driver for the effective filtering, so the collection and the formatting are driven directly.
 */
class AtlasLoadStatsTest {

    @Test
    void collectsWhatTheHooksRecordForOneLoad() {
        AtlasLoadStats.begin();
        assertTrue(AtlasLoadStats.isCollecting());

        AtlasLoadStats.recordStitch(1_000_000L);
        AtlasLoadStats.recordSprites(7);
        AtlasLoadStats.recordPrepare(2_000_000L);
        AtlasLoadStats.recordAssemble(3_000_000L);
        AtlasLoadStats.recordDecode(7, 6, 1, true);
        AtlasLoadStats.recordMipmapsOffloaded(5);
        AtlasLoadStats.recordBatchedUploads(4);
        AtlasLoadStats.recordPrepared(6);
        AtlasLoadStats.recordStrategy(MipmapStrategy.CUTOUT);
        AtlasLoadStats.recordStrategy(MipmapStrategy.CUTOUT);
        AtlasLoadStats.recordStrategy(MipmapStrategy.MEAN);
        AtlasLoadStats.recordForeignMipmapCall();
        AtlasLoadStats.recordForeignMipmapCall();

        final AtlasLoadStats.Values values = AtlasLoadStats.snapshot("textures/blocks", 4, 16,
            TextureFilterMode.RGSS_ANISOTROPIC, 4, true, 9_000_000L);

        assertEquals("textures/blocks", values.atlas());
        assertEquals(7, values.sprites());
        assertEquals(4, values.mipLevels());
        assertEquals(16, values.gutter());
        assertEquals(TextureFilterMode.RGSS_ANISOTROPIC, values.filterMode());
        assertEquals(4, values.anisotropy());
        assertTrue(values.texelSnap());
        assertTrue(values.parallelDecode(), "a seeded decode lane is what makes the decode parallel");
        assertTrue(values.parallelMipmaps());
        assertTrue(values.batchedUpload());
        assertEquals(7, values.decodeSubmitted());
        assertEquals(6, values.decoded());
        assertEquals(1, values.decodeFailures());
        assertTrue(values.decodeDesynced());
        assertEquals(5, values.mipmapsOffloaded());
        assertEquals(4, values.batchedUploaded());
        assertEquals(6, values.prepared());
        assertEquals(1_000_000L, values.stitchNanos());
        assertEquals(2_000_000L, values.prepareNanos());
        assertEquals(3_000_000L, values.assembleNanos());
        assertEquals(9_000_000L, values.totalNanos());
        assertEquals(Map.of(MipmapStrategy.CUTOUT, 2, MipmapStrategy.MEAN, 1), values.strategies());
        assertEquals(2, values.foreignMipmapCalls(), "a call the load did not generate for is reported, not counted as one of its sprites");
    }

    @Test
    void keepsCountingForeignCallsWithoutAddingThemToTheStrategyCounts() {
        AtlasLoadStats.begin();

        AtlasLoadStats.recordStrategy(MipmapStrategy.MEAN);
        AtlasLoadStats.recordForeignMipmapCall();
        AtlasLoadStats.recordForeignMipmapCall();
        AtlasLoadStats.recordForeignMipmapCall();

        final AtlasLoadStats.Values values = AtlasLoadStats.snapshot("textures/blocks", 4, 16,
            TextureFilterMode.NONE, 1, false, 1L);

        assertEquals(3, values.foreignMipmapCalls(), "the count only grows, once per call");
        assertEquals(Map.of(MipmapStrategy.MEAN, 1), values.strategies(), "a foreign call is no sprite of this load");
    }

    @Test
    void reportsAnIdleLoadAsIdleAndIgnoresRecordsThatArriveOutsideIt() {
        AtlasLoadStats.begin();
        AtlasLoadStats.recordSprites(3);
        AtlasLoadStats.snapshot("textures/blocks", 0, 1, TextureFilterMode.NONE, 1, false, 1L);

        assertFalse(AtlasLoadStats.isCollecting(), "taking the report ends the collection");

        AtlasLoadStats.recordSprites(99);
        AtlasLoadStats.recordStrategy(MipmapStrategy.DARK_CUTOUT);
        AtlasLoadStats.recordForeignMipmapCall();
        final AtlasLoadStats.Values idle = AtlasLoadStats.snapshot("textures/blocks", 0, 1,
            TextureFilterMode.NONE, 1, false, 1L);

        assertEquals(0, idle.sprites(), "nothing recorded outside a load belongs to it");
        assertTrue(idle.strategies().isEmpty());
        assertEquals(0, idle.foreignMipmapCalls(), "a call outside a load belongs to no report");
        assertFalse(idle.parallelDecode());
        assertFalse(idle.parallelMipmaps());
        assertFalse(idle.batchedUpload());
    }

    @Test
    void describesOneLoadOnASingleLine() {
        final Map<MipmapStrategy, Integer> strategies = new EnumMap<>(MipmapStrategy.class);
        strategies.put(MipmapStrategy.CUTOUT, 12);
        strategies.put(MipmapStrategy.STRICT_CUTOUT, 3);

        final AtlasLoadStats.Values values = new AtlasLoadStats.Values("textures/blocks", 100, 4, 16,
            TextureFilterMode.RGSS, 1, true, 8, true, true, true, 100, 99, 1, false, 90, 98, 100,
            1_000_000L, 2_000_000L, 3_000_000L, 7_000_000L, strategies, 7);

        assertEquals("Atlas build: atlas=textures/blocks sprites=100 mipLevels=4 gutter=16 filter=RGSS"
            + " anisotropy=1x texelSnap=true workers=8"
            + " parallel=[decode=true,mipmaps=true,batchedUpload=true,submitted=100,decoded=99,decodeFailures=1"
            + ",decodeDesync=false,mipmapsOffloaded=90,batchedUploaded=98,prepared=100]"
            + " phases=[stitchMs=1.000,prepareMs=2.000,assembleMs=3.000,totalMs=7.000]"
            + " strategies=[CUTOUT=12,STRICT_CUTOUT=3] foreignMipmapCalls=7", AtlasLoadStats.describe(values));
    }
}
