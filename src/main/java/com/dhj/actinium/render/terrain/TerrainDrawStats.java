package com.dhj.actinium.render.terrain;

/**
 * Running totals for terrain buffer work, read by profiling output.
 */
public final class TerrainDrawStats {
    private static long indexBufferGrowths;
    private static long sectionsUploaded;
    private static long bytesUploaded;

    private TerrainDrawStats() {
    }

    public static void recordIndexBufferGrowth() {
        indexBufferGrowths++;
    }

    public static void recordSectionUpload(long bytes) {
        sectionsUploaded++;
        bytesUploaded += bytes;
    }

    public static long getIndexBufferGrowths() {
        return indexBufferGrowths;
    }

    public static long getSectionsUploaded() {
        return sectionsUploaded;
    }

    public static long getBytesUploaded() {
        return bytesUploaded;
    }
}
