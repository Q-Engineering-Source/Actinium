package net.coderbot.iris.uniforms;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two hardcoded entity names ({@code minecraft:current_player} and
 * {@code minecraft:zombie_villager_converting}) are resolved once per entity id map and cached,
 * rather than being looked up for every entity of the matching type on every frame.
 * <p>
 * The entity-facing entry point cannot be driven here: building any {@code net.minecraft.entity.Entity}
 * needs the game Bootstrap. The cache itself is therefore driven directly.
 */
class EntityIdHelperSpecialIdCacheTest {
    private static final NamespacedId CURRENT_PLAYER = new NamespacedId("minecraft", "current_player");
    private static final NamespacedId CONVERTING_VILLAGER = new NamespacedId("minecraft", "zombie_villager_converting");

    @Test
    void resolvesEachNameOncePerEntityIdMap() {
        final EntityIdHelper.SpecialIds specialIds = new EntityIdHelper.SpecialIds();
        final CountingEntityIdMap entityIds = new CountingEntityIdMap();
        entityIds.put(CURRENT_PLAYER, 99);
        entityIds.put(CONVERTING_VILLAGER, 98);

        specialIds.refresh(entityIds);

        assertEquals(99, specialIds.currentPlayer());
        assertEquals(98, specialIds.convertingVillager());
        assertEquals(1, entityIds.lookups(CURRENT_PLAYER), "the name is resolved when the map is first used");
        assertEquals(1, entityIds.lookups(CONVERTING_VILLAGER), "the name is resolved when the map is first used");

        specialIds.refresh(entityIds);

        assertEquals(99, specialIds.currentPlayer());
        assertEquals(98, specialIds.convertingVillager());
        assertEquals(1, entityIds.lookups(CURRENT_PLAYER), "a cached name must not be resolved again");
        assertEquals(1, entityIds.lookups(CONVERTING_VILLAGER), "a cached name must not be resolved again");
    }

    @Test
    void resolvesBothNamesAgainForAReloadedEntityIdMap() {
        final EntityIdHelper.SpecialIds specialIds = new EntityIdHelper.SpecialIds();
        final CountingEntityIdMap firstPack = new CountingEntityIdMap();
        firstPack.put(CURRENT_PLAYER, 99);
        firstPack.put(CONVERTING_VILLAGER, 98);
        specialIds.refresh(firstPack);

        final CountingEntityIdMap secondPack = new CountingEntityIdMap();
        secondPack.put(CURRENT_PLAYER, 7);
        secondPack.put(CONVERTING_VILLAGER, 6);
        specialIds.refresh(secondPack);

        assertEquals(7, specialIds.currentPlayer(), "the reloaded map resolves through its own ids");
        assertEquals(6, specialIds.convertingVillager(), "the reloaded map resolves through its own ids");
        assertEquals(1, secondPack.lookups(CURRENT_PLAYER));
        assertEquals(1, secondPack.lookups(CONVERTING_VILLAGER));
        assertEquals(1, firstPack.lookups(CURRENT_PLAYER), "the replaced map is not consulted anymore");
    }

    @Test
    void reportsNamesTheEntityIdMapDoesNotDefine() {
        final EntityIdHelper.SpecialIds specialIds = new EntityIdHelper.SpecialIds();
        final CountingEntityIdMap entityIds = new CountingEntityIdMap();

        specialIds.refresh(entityIds);

        assertEquals(-1, specialIds.currentPlayer(), "an undefined name must stay undefined");
        assertEquals(-1, specialIds.convertingVillager(), "an undefined name must stay undefined");
        assertEquals(1, entityIds.lookups(CURRENT_PLAYER), "an undefined name is resolved once as well");
    }

    /** Entity id map that records how often each name is resolved, to observe the caching. */
    private static final class CountingEntityIdMap extends Object2IntOpenHashMap<NamespacedId> {
        private final Object2IntOpenHashMap<NamespacedId> lookups = new Object2IntOpenHashMap<>();

        private CountingEntityIdMap() {
            defaultReturnValue(-1);
        }

        @Override
        public int getInt(Object key) {
            lookups.addTo((NamespacedId) key, 1);
            return super.getInt(key);
        }

        private int lookups(NamespacedId id) {
            return lookups.getInt(id);
        }
    }
}
