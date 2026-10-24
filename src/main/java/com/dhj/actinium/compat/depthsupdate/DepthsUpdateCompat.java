package com.dhj.actinium.compat.depthsupdate;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.minecraft.world.World;
import sayys.depthsupdate.api.DepthsUpdateAPI;
import sayys.depthsupdate.api.HeightInfo;

/**
 * Compatibility for {@code Depths Update} (mod id {@code depthsupdate}); fixes issue #68.
 *
 * <p>Depths Update backports the Caves &amp; Cliffs world height (default {@code -64..320}) and
 * reshapes {@code Chunk.storageArrays}: vanilla sections keep their index {@code 0..15}, upper
 * sections keep identity above that, negative sections are parked at the tail. Actinium's renderer
 * assumed the vanilla {@code 0..255} layout, so out-of-range sections were never created and the
 * blocks in them were not rendered. This shim exposes the extended height range and the
 * storage-array index mapping from the {@code sayys.depthsupdate.api} package, falling back to
 * the vanilla identity mapping without the mod.
 */
public final class DepthsUpdateCompat {
    public static final boolean IS_LOADED = Mods.DEPTHSUPDATE;

    private static final int VANILLA_MIN_SECTION = 0;
    private static final int VANILLA_MAX_SECTION = 16;
    private static final int VANILLA_SECTION_COUNT = 16;

    private DepthsUpdateCompat() {
    }

    /**
     * Returns the inclusive/vanilla section index of the lowest build section for {@code world}.
     */
    public static int getMinSection(World world) {
        HeightInfo info = heightInfo(world);
        return (info != null && info.isExtended()) ? info.minY() >> 4 : VANILLA_MIN_SECTION;
    }

    /**
     * Returns the exclusive upper bound of the build sections for {@code world} (sections are created
     * for {@code y in [minSection, maxSection)}).
     */
    public static int getMaxSection(World world) {
        HeightInfo info = heightInfo(world);
        return (info != null && info.isExtended()) ? ((info.maxY() - 1) >> 4) + 1 : VANILLA_MAX_SECTION;
    }

    /**
     * Returns the number of sections the world spans vertically.
     */
    public static int getSectionCount(World world) {
        HeightInfo info = heightInfo(world);
        return (info != null && info.isExtended()) ? (info.maxY() - info.minY()) >> 4 : VANILLA_SECTION_COUNT;
    }

    /**
     * Returns the lowest build height (inclusive) of {@code world}.
     */
    public static int getMinBuildHeight(World world) {
        HeightInfo info = heightInfo(world);
        return (info != null && info.isExtended()) ? info.minY() : 0;
    }

    /**
     * Maps a semantic section {@code sectionY} to the array index inside
     * {@code Chunk.getBlockStorageArray()} that holds that section's data, or {@code -1} when the
     * section is outside the world's height range. For vanilla heights this is the identity mapping.
     */
    public static int toStorageIndex(World world, int sectionY) {
        HeightInfo info = heightInfo(world);
        if (info == null || !info.isExtended()) {
            return (sectionY >= 0 && sectionY < VANILLA_SECTION_COUNT) ? sectionY : -1;
        }

        int minSection = info.minY() >> 4;
        int maxSectionInclusive = (info.maxY() - 1) >> 4;

        // Vanilla and upper sections are stored at their own array index.
        if (sectionY >= 0 && sectionY <= maxSectionInclusive) {
            return sectionY;
        }

        // Negative sections are parked at the tail, ordered so the lowest section has the highest index.
        if (sectionY >= minSection && sectionY < 0) {
            return maxSectionInclusive - sectionY;
        }

        return -1;
    }

    private static HeightInfo heightInfo(World world) {
        return IS_LOADED ? DepthsUpdateAPI.getHeightInfo(world) : null;
    }
}
