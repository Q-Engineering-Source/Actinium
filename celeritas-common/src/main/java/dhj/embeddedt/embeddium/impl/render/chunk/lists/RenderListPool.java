package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;

/**
 * Recycles the per-search {@link ChunkRenderList} containers of one {@link RenderListManager}.
 *
 * <p>Two slots alternate between consecutive searches. The render lists published by the last
 * search are therefore never the containers the next search writes into, which is what lets the
 * render thread keep drawing from them while the search thread fills the other slot. Two slots are
 * enough only because {@code RenderListManager#submitSearch} rejects a second in-flight search for
 * the same manager.
 *
 * <p>Each slot keeps the ordered render lists of its current search (the draw order) and, indexed
 * by region id, the lists of earlier searches so their section arrays can be reused. A per-region
 * stamp records which search last claimed a list, so a list that is only being kept for reuse is
 * never mistaken for a member of the current search.
 */
public final class RenderListPool {
    private static final int SLOT_COUNT = 2;

    private final Slot[] slots = new Slot[SLOT_COUNT];

    private int generation;

    public RenderListPool() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.slots[i] = new Slot();
        }
    }

    /**
     * Starts a search and returns the slot it must fill. The returned slot's ordered list is empty;
     * the lists of earlier searches stay available for reuse.
     *
     * @param regionIdsLength number of region ids the search can encounter
     */
    public Slot beginSearch(int regionIdsLength) {
        Slot slot = this.slots[this.generation++ & (SLOT_COUNT - 1)];
        slot.begin(regionIdsLength);
        return slot;
    }

    /** One rotating set of render list containers. */
    public static final class Slot {
        private final ObjectArrayList<ChunkRenderList> lists = new ObjectArrayList<>();
        private ChunkRenderList[] listsByRegion = new ChunkRenderList[0];
        private int[] searchOfRegion = new int[0];
        private int search;

        private Slot() {
        }

        /** Ordered render lists of the current search; backs its {@link SortedRenderLists}. */
        public ObjectArrayList<ChunkRenderList> lists() {
            return this.lists;
        }

        /** Returns the list the current search already built for the region id, or {@code null}. */
        public ChunkRenderList get(int regionId) {
            if (regionId < 0 || regionId >= this.listsByRegion.length || this.searchOfRegion[regionId] != this.search) {
                return null;
            }

            return this.listsByRegion[regionId];
        }

        /**
         * Claims the slot's list for {@code region}: the list of an earlier search is reset and
         * reused when it belongs to the same region instance, otherwise a fresh list is created.
         * The claimed list is appended to the draw order of the current search.
         */
        public ChunkRenderList acquire(RenderRegion region) {
            int regionId = region.getId();

            if (regionId < 0 || regionId >= this.listsByRegion.length) {
                throw new IllegalStateException("Region id " + regionId + " outside the search's "
                        + this.listsByRegion.length + " ids");
            }

            ChunkRenderList list = this.listsByRegion[regionId];

            if (list != null && list.getRegion() == region) {
                list.reset();
            } else {
                list = new ChunkRenderList(region);
                this.listsByRegion[regionId] = list;
            }

            this.searchOfRegion[regionId] = this.search;
            this.lists.add(list);
            return list;
        }

        private void begin(int regionIdsLength) {
            this.lists.clear();
            this.search++;

            if (this.listsByRegion.length < regionIdsLength) {
                this.listsByRegion = Arrays.copyOf(this.listsByRegion, regionIdsLength);
                this.searchOfRegion = Arrays.copyOf(this.searchOfRegion, regionIdsLength);
            }
        }
    }
}
