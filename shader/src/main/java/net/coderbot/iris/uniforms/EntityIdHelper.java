package net.coderbot.iris.uniforms;

import it.unimi.dsi.fastutil.ints.Int2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.block_rendering.NbtConditionalIdMap;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.monster.EntityZombieVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves shader entity IDs with legacy-name, special-case and NBT support.
 */
public final class EntityIdHelper {
    private static final NamespacedId CONVERTING_VILLAGER = new NamespacedId("minecraft", "zombie_villager_converting");
    private static final NamespacedId CURRENT_PLAYER = new NamespacedId("minecraft", "current_player");
    private static final NamespacedId PLAYER_ID = new NamespacedId("minecraft", "player");
    private static final NamespacedId LIGHTNING_BOLT_ID = new NamespacedId("minecraft", "lightning_bolt");

    private static final Object2IntMap<Class<?>> ENTITY_ID_CACHE = new Object2IntOpenHashMap<>();
    private static final Map<Class<?>, NamespacedId> ENTITY_NAME_CACHE = new IdentityHashMap<>();
    private static Object2IntFunction<NamespacedId> cachedEntityIdMap;
    private static final SpecialIds SPECIAL_IDS = new SpecialIds();

    private static final Int2LongLinkedOpenHashMap ENTITY_NBT_CACHE = new Int2LongLinkedOpenHashMap();
    private static final int NBT_CACHE_INTERVAL_TICKS = 20;
    private static final int ENTITY_NBT_CACHE_MAX = 256;

    /** Resolutions since the last report, for the periodic segment that shows which caches carry the work. */
    private static final AtomicLong specialQueries = new AtomicLong();
    private static final AtomicLong specialResolves = new AtomicLong();
    private static final AtomicLong specialHits = new AtomicLong();
    private static final AtomicLong idCacheHits = new AtomicLong();
    private static final AtomicLong idCacheMisses = new AtomicLong();

    static {
        ENTITY_ID_CACHE.defaultReturnValue(Integer.MIN_VALUE);
        ENTITY_NBT_CACHE.defaultReturnValue(-1L);
    }

    private EntityIdHelper() {
    }

    public static boolean isLightningBolt(Entity entity) {
        return entity instanceof EntityLightningBolt;
    }

    public static int getEntityId(Entity entity) {
        Object2IntFunction<NamespacedId> entityIdMap = BlockRenderingSettings.INSTANCE.getEntityIds();
        if (entityIdMap == null) {
            return -1;
        }

        SPECIAL_IDS.refresh(entityIdMap);

        if (entityIdMap != cachedEntityIdMap) {
            ENTITY_ID_CACHE.clear();
            ENTITY_NAME_CACHE.clear();
            ENTITY_NBT_CACHE.clear();
            cachedEntityIdMap = entityIdMap;
        }

        NbtConditionalIdMap<NamespacedId> entityNbtMap = BlockRenderingSettings.INSTANCE.getEntityNbtMap();
        if (entityNbtMap != null && !entityNbtMap.isEmpty() && entity.world != null) {
            NamespacedId namespacedId = getCachedEntityName(entity);
            if (namespacedId != null && entityNbtMap.hasConditions(namespacedId)) {
                int entityRuntimeId = entity.getEntityId();
                long currentTick = entity.world.getTotalWorldTime();
                long cached = ENTITY_NBT_CACHE.get(entityRuntimeId);

                int nbtId;
                if (cached != -1L && (currentTick - (cached >>> 32)) < NBT_CACHE_INTERVAL_TICKS) {
                    nbtId = (int) cached;
                } else {
                    NBTTagCompound nbt = new NBTTagCompound();
                    entity.writeToNBT(nbt);
                    nbtId = entityNbtMap.resolve(namespacedId, nbt);
                    long packed = ((currentTick & 0x7FFFFFFFL) << 32) | (nbtId & 0xFFFFFFFFL);
                    ENTITY_NBT_CACHE.put(entityRuntimeId, packed);
                    while (ENTITY_NBT_CACHE.size() > ENTITY_NBT_CACHE_MAX) {
                        ENTITY_NBT_CACHE.removeFirstLong();
                    }
                }

                if (nbtId != -1) {
                    return nbtId;
                }
            }
        }

        int normalId = getNormalEntityId(entity, entityIdMap);
        int specialId = getSpecialEntityId(entity);
        if (specialId != -1) {
            return specialId;
        }

        return normalId;
    }

    private static int getSpecialEntityId(Entity entity) {
        specialQueries.incrementAndGet();
        final int currentPlayerId = SPECIAL_IDS.currentPlayer();
        if (currentPlayerId != -1 && entity instanceof EntityPlayer && entity == Minecraft.getMinecraft().getRenderViewEntity()) {
            specialHits.incrementAndGet();
            return currentPlayerId;
        }

        if (entity instanceof EntityZombieVillager zombieVillager) {
            if (zombieVillager.isConverting()) {
                final int convertingVillagerId = SPECIAL_IDS.convertingVillager();
                if (convertingVillagerId != -1) {
                    specialHits.incrementAndGet();
                }
                return convertingVillagerId;
            }
        }

        return -1;
    }

    private static int getNormalEntityId(Entity entity, Object2IntFunction<NamespacedId> entityIdMap) {
        Class<?> entityClass = entity.getClass();
        int cached = ENTITY_ID_CACHE.getInt(entityClass);
        if (cached != Integer.MIN_VALUE) {
            idCacheHits.incrementAndGet();
            return cached;
        }

        idCacheMisses.incrementAndGet();
        int resolvedId = resolveEntityId(entity, entityClass, entityIdMap);
        ENTITY_ID_CACHE.put(entityClass, resolvedId);
        return resolvedId;
    }

    /**
     * The periodic report's entity-id segment, or an empty string while nothing was resolved. The
     * {@code specialResolves} count is the ported optimisation made visible: the two hardcoded names are
     * resolved once per entity id map rather than once per entity that matches them, so it stays at one
     * per pack load however many players and converting villagers are drawn.
     */
    public static String statsSummary() {
        final long queries = specialQueries.getAndSet(0L);
        final long resolves = specialResolves.getAndSet(0L);
        final long hits = specialHits.getAndSet(0L);
        final long cacheHits = idCacheHits.getAndSet(0L);
        final long cacheMisses = idCacheMisses.getAndSet(0L);
        if (queries == 0L && resolves == 0L && cacheHits == 0L && cacheMisses == 0L) {
            return "";
        }
        return "entityId[specialQueries=" + queries
            + ",specialResolves=" + resolves
            + ",specialHits=" + hits
            + ",idCacheHits=" + cacheHits
            + ",idCacheMisses=" + cacheMisses
            + ']';
    }

    private static int resolveEntityId(Entity entity, Class<?> entityClass, Object2IntFunction<NamespacedId> entityIdMap) {
        NamespacedId registryName = getRegistryEntityName(entity);
        if (registryName != null) {
            int id = entityIdMap.getInt(registryName);
            if (id != -1) {
                return id;
            }
        }

        if (entity instanceof EntityPlayer) {
            int id = entityIdMap.getInt(PLAYER_ID);
            if (id != -1) {
                return id;
            }
        }

        String entityType = EntityList.getEntityString(entity);
        if (entityType != null) {
            int namespaced = entityIdMap.getInt(new NamespacedId(entityType));
            if (namespaced != -1) {
                return namespaced;
            }
        }

        String simpleClassName = entityClass.getSimpleName();
        int id = entityIdMap.getInt(new NamespacedId(simpleClassName));

        if (id == -1) {
            String className = entityClass.getName();
            id = entityIdMap.getInt(new NamespacedId(className));
        }

        if (id == -1 && entity instanceof EntityLightningBolt) {
            id = entityIdMap.getInt(LIGHTNING_BOLT_ID);
        }

        return id;
    }

    private static NamespacedId getCachedEntityName(Entity entity) {
        Class<?> entityClass = entity.getClass();
        NamespacedId cached = ENTITY_NAME_CACHE.get(entityClass);
        if (cached != null) {
            return cached;
        }

        cached = getRegistryEntityName(entity);
        if (cached == null) {
            if (entity instanceof EntityPlayer) {
                cached = PLAYER_ID;
            } else if (entity instanceof EntityLightningBolt) {
                cached = LIGHTNING_BOLT_ID;
            } else {
                String entityType = EntityList.getEntityString(entity);
                if (entityType == null) {
                    return null;
                }

                cached = new NamespacedId(entityType);
            }
        }

        ENTITY_NAME_CACHE.put(entityClass, cached);
        return cached;
    }

    private static NamespacedId getRegistryEntityName(Entity entity) {
        ResourceLocation key = EntityList.getKey(entity);
        if (key == null) {
            return null;
        }

        return new NamespacedId(key.getNamespace(), key.getPath());
    }

    /**
     * The hardcoded entity names as resolved by the entity id map in use.
     * <p>
     * A shaderpack either defines these names or it does not, so they are resolved once per map
     * rather than once per matching entity: a miss repeated for every player and every converting
     * villager, on every frame, is pure overhead. A reloaded pack installs a new map, which is what
     * makes the values resolve again.
     */
    static final class SpecialIds {
        private Object2IntFunction<NamespacedId> source;
        private int currentPlayer = -1;
        private int convertingVillager = -1;

        /**
         * Re-resolves both names when the given map is not the one already resolved.
         */
        void refresh(Object2IntFunction<NamespacedId> entityIdMap) {
            if (entityIdMap == this.source) {
                return;
            }

            this.source = entityIdMap;
            this.currentPlayer = entityIdMap.getInt(CURRENT_PLAYER);
            this.convertingVillager = entityIdMap.getInt(CONVERTING_VILLAGER);
            specialResolves.incrementAndGet();
        }

        /**
         * The id the current player is drawn with, or -1 when the pack does not define it.
         */
        int currentPlayer() {
            return this.currentPlayer;
        }

        /**
         * The id a converting zombie villager is drawn with, or -1 when the pack does not define it.
         */
        int convertingVillager() {
            return this.convertingVillager;
        }
    }
}
