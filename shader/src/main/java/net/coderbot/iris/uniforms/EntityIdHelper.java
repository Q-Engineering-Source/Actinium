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

    private static final Int2LongLinkedOpenHashMap ENTITY_NBT_CACHE = new Int2LongLinkedOpenHashMap();
    private static final int NBT_CACHE_INTERVAL_TICKS = 20;
    private static final int ENTITY_NBT_CACHE_MAX = 256;

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
        int specialId = getSpecialEntityId(entity, entityIdMap);
        if (specialId != -1) {
            return specialId;
        }

        return normalId;
    }

    private static int getSpecialEntityId(Entity entity, Object2IntFunction<NamespacedId> entityIdMap) {
        Entity cameraEntity = Minecraft.getMinecraft().getRenderViewEntity();
        if (entity == cameraEntity && entity instanceof EntityPlayer) {
            int currentPlayerId = entityIdMap.getInt(CURRENT_PLAYER);
            if (currentPlayerId != -1) {
                return currentPlayerId;
            }
        }

        if (entity instanceof EntityZombieVillager zombieVillager) {
            if (zombieVillager.isConverting()) {
                return entityIdMap.getInt(CONVERTING_VILLAGER);
            }
        }

        return -1;
    }

    private static int getNormalEntityId(Entity entity, Object2IntFunction<NamespacedId> entityIdMap) {
        Class<?> entityClass = entity.getClass();
        int cached = ENTITY_ID_CACHE.getInt(entityClass);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }

        int resolvedId = resolveEntityId(entity, entityClass, entityIdMap);
        ENTITY_ID_CACHE.put(entityClass, resolvedId);
        return resolvedId;
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

    /** A display list retains the entity's material identity without retaining its world. */
    public record EntityIdentity(Class<?> type, NamespacedId registeredName, boolean currentPlayer,
                                 boolean convertingVillager, NBTTagCompound nbt) {
        public int resolve() {
            final Object2IntFunction<NamespacedId> ids = currentEntityIds();
            if (ids == null) return -1;
            if (nbt != null && registeredName != null) {
                final NbtConditionalIdMap<NamespacedId> conditions = BlockRenderingSettings.INSTANCE.getEntityNbtMap();
                if (conditions != null && !conditions.isEmpty() && conditions.hasConditions(registeredName)) {
                    final int conditional = conditions.resolve(registeredName, nbt);
                    if (conditional != -1) return conditional;
                }
            }
            if (currentPlayer) {
                final int playerId = ids.getInt(PLAYER_ID);
                if (playerId != -1) return playerId;
            }
            return getNormalEntityId(type, registeredName, ids);
        }
    }

    public static EntityIdentity snapshot(Entity entity) {
        if (entity == null) return null;
        NBTTagCompound nbt = null;
        if (entity.world != null) {
            nbt = new NBTTagCompound();
            entity.writeToNBT(nbt);
            nbt = (NBTTagCompound) nbt.copy();
        }
        final String registeredName = EntityList.getEntityString(entity);
        return new EntityIdentity(entity.getClass(), registeredName == null ? null : new NamespacedId(registeredName),
            entity instanceof EntityPlayer && entity == Minecraft.getMinecraft().getRenderViewEntity(),
            entity instanceof EntityZombieVillager villager && villager.isConverting(), nbt);
    }

    private static Object2IntFunction<NamespacedId> currentEntityIds() {
        final Object2IntFunction<NamespacedId> ids = BlockRenderingSettings.INSTANCE.getEntityIds();
        if (ids != cachedEntityIdMap) {
            ENTITY_ID_CACHE.clear();
            ENTITY_NAME_CACHE.clear();
            ENTITY_NBT_CACHE.clear();
            cachedEntityIdMap = ids;
        }
        return ids;
    }

    private static int getNormalEntityId(Class<?> entityClass, NamespacedId registeredName,
                                         Object2IntFunction<NamespacedId> entityIdMap) {
        final int cached = ENTITY_ID_CACHE.getInt(entityClass);
        if (cached != Integer.MIN_VALUE) return cached;

        int resolvedId = -1;
        if (registeredName != null) {
            resolvedId = entityIdMap.getInt(registeredName);
        }
        if (resolvedId == -1 && EntityPlayer.class.isAssignableFrom(entityClass)) {
            resolvedId = entityIdMap.getInt(PLAYER_ID);
        }
        if (resolvedId == -1) {
            resolvedId = entityIdMap.getInt(new NamespacedId(entityClass.getSimpleName()));
        }
        if (resolvedId == -1) {
            resolvedId = entityIdMap.getInt(new NamespacedId(entityClass.getName()));
        }
        ENTITY_ID_CACHE.put(entityClass, resolvedId);
        return resolvedId;
    }
}
