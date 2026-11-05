package com.dhj.actinium.render.terrain;

import com.dhj.actinium.config.ActiniumConfig;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Tracks 3D cubes instead of columns; the inherited 2D state stays empty.
 *
 * <p>Cubes are keyed by a packed x/y/z triple: the 1.12.2 CubicChunks API has no 3D hash container, so the 1.7.10
 * upstream {@code HashSet3D}/{@code HashMap3D} become fastutil long sets.
 */
public class CubeStatusTracker extends ChunkTracker {

    private static final byte PENDING_ADD = 1;
    private static final byte PENDING_REMOVE = 2;

    /** Cubes that have been synced to the client and are currently loaded. */
    private final LongSet loadedCubes = new LongOpenHashSet();
    /** Cubes that have their neighbours loaded (as needed) and are ready to be rendered. */
    private final LongSet validCubes = new LongOpenHashSet();

    private final Long2ByteMap changedCubes = new Long2ByteOpenHashMap();

    /** When true, adjacent cubes will not be checked. */
    private boolean fastMode;

    public CubeStatusTracker() {
    }

    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;

        for (LongIterator it = this.loadedCubes.iterator(); it.hasNext(); ) {
            pollCube(it.nextLong());
        }
    }

    public boolean isFastModeEnabled() {
        return this.fastMode;
    }

    public void onCubeLoaded(int cubeX, int cubeY, int cubeZ) {
        if (this.loadedCubes.add(pack(cubeX, cubeY, cubeZ))) {
            onCubeChanged(cubeX, cubeY, cubeZ);
        }
    }

    public void onCubeUnloaded(int cubeX, int cubeY, int cubeZ) {
        if (this.loadedCubes.remove(pack(cubeX, cubeY, cubeZ))) {
            onCubeChanged(cubeX, cubeY, cubeZ);
        }
    }

    private void onCubeChanged(int cubeX, int cubeY, int cubeZ) {
        pollCube(pack(cubeX, cubeY, cubeZ));

        if (this.fastMode) {
            return;
        }

        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    pollCube(pack(cubeX + dx, cubeY + dy, cubeZ + dz));
                }
            }
        }
    }

    private void pollCube(long key) {
        boolean renderable = canRender(key);
        boolean isRendering = this.validCubes.contains(key);

        if (renderable != isRendering) {
            this.changedCubes.put(key, renderable ? PENDING_ADD : PENDING_REMOVE);
        } else {
            this.changedCubes.remove(key);
        }
    }

    private boolean canRender(long key) {
        if (!this.loadedCubes.contains(key)) {
            return false;
        }

        if (this.fastMode) {
            return true;
        }

        int cubeX = unpackX(key);
        int cubeY = unpackY(key);
        int cubeZ = unpackZ(key);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    if (!this.loadedCubes.contains(pack(cubeX + dx, cubeY + dy, cubeZ + dz))) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    @Override
    public void onChunkStatusAdded(int x, int z, int flags) {
        // do nothing
    }

    @Override
    public void onChunkStatusRemoved(int x, int z, int flags) {
        // do nothing
    }

    public void forEachReady(RenderSectionManager renderSectionManager) {
        syncFastMode();

        applyPendingChanges();

        for (LongIterator it = this.validCubes.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            renderSectionManager.onSectionAdded(unpackX(key), unpackY(key), unpackZ(key));
        }
    }

    public void forEachEvent(RenderSectionManager renderSectionManager) {
        syncFastMode();

        for (Long2ByteMap.Entry entry : this.changedCubes.long2ByteEntrySet()) {
            long key = entry.getLongKey();
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);

            if (entry.getByteValue() == PENDING_ADD) {
                this.validCubes.add(key);
                renderSectionManager.onSectionAdded(x, y, z);
            } else {
                this.validCubes.remove(key);
                renderSectionManager.onSectionRemoved(x, y, z);
            }
        }

        this.changedCubes.clear();
    }

    private void syncFastMode() {
        if (this.isFastModeEnabled() != ActiniumConfig.useVanillaChunkTracking) {
            setFastMode(ActiniumConfig.useVanillaChunkTracking);
        }
    }

    /** Folds pending changes and reports how many cubes {@link #forEachReady} would publish. */
    int readyCubeCount() {
        applyPendingChanges();
        return this.validCubes.size();
    }

    private void applyPendingChanges() {
        for (Long2ByteMap.Entry entry : this.changedCubes.long2ByteEntrySet()) {
            if (entry.getByteValue() == PENDING_ADD) {
                this.validCubes.add(entry.getLongKey());
            } else {
                this.validCubes.remove(entry.getLongKey());
            }
        }

        this.changedCubes.clear();
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    private static int unpackX(long key) {
        return (int) (key >> 42);
    }

    private static int unpackY(long key) {
        return (int) (key << 22 >> 42);
    }

    private static int unpackZ(long key) {
        return (int) (key << 42 >> 42);
    }
}
