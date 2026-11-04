package com.dhj.actinium.render.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainDrawStatsTest {
    @Test
    void accumulatesSectionCountAndBytesAcrossUploads() {
        long sectionsBefore = TerrainDrawStats.getSectionsUploaded();
        long bytesBefore = TerrainDrawStats.getBytesUploaded();

        TerrainDrawStats.recordSectionUpload(1000L);
        TerrainDrawStats.recordSectionUpload(2500L);

        assertEquals(sectionsBefore + 2, TerrainDrawStats.getSectionsUploaded());
        assertEquals(bytesBefore + 3500L, TerrainDrawStats.getBytesUploaded());
    }

    @Test
    void countsEachIndexBufferGrowth() {
        long before = TerrainDrawStats.getIndexBufferGrowths();

        TerrainDrawStats.recordIndexBufferGrowth();
        TerrainDrawStats.recordIndexBufferGrowth();
        TerrainDrawStats.recordIndexBufferGrowth();

        assertEquals(before + 3, TerrainDrawStats.getIndexBufferGrowths());
    }
}
