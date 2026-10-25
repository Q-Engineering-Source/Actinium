package net.coderbot.iris.uniforms;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.block_rendering.NbtConditionalIdMap;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * Helper class for resolving material IDs for items.
 * Checks both item.properties and block.properties.
 */
public class ItemMaterialHelper {
    private static final Reference2ObjectMap<Item, Int2IntMap> MATERIAL_CACHE = new Reference2ObjectOpenHashMap<>();
    private static final int CACHE_MISS_SENTINEL = Integer.MIN_VALUE;
    /**
     * Get the material ID for an ItemStack.
     * For ItemBlock: checks block.properties first, then item.properties.
     * For other items: checks item.properties only.
     *
     * @return The material ID, or -1 if not found
     */
    public static int getMaterialId(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty() || itemStack.getItem() == null) {
            return -1;
        }
        return getMaterialId(itemStack.getItem(), itemStack.getItemDamage());
    }


    public static int getMaterialId(Item item, int metadata) {
        if (item == null) {
            return -1;
        }

        Int2IntMap metadataCache = MATERIAL_CACHE.get(item);
        if (metadataCache != null) {
            int cached = metadataCache.getOrDefault(metadata, CACHE_MISS_SENTINEL);
            if (cached != CACHE_MISS_SENTINEL) {
                return cached;
            }
        }

        int materialId = lookupMaterialId(item, metadata);

        if (metadataCache == null) {
            metadataCache = new Int2IntOpenHashMap();
            metadataCache.defaultReturnValue(CACHE_MISS_SENTINEL);
            MATERIAL_CACHE.put(item, metadataCache);
        }
        metadataCache.put(metadata, materialId);

        return materialId;
    }

    /**
     * Perform the actual material ID lookup without caching.
     *
     * For block items (ItemBlock): use block.properties first, then fall back to item.properties.
     * For non-block items: use item.properties only.
     *
     * This matches modern Iris behavior where block items use block IDs and non-block items use item IDs.
     */
    private static int lookupMaterialId(Item item, int metadata) {
        // For ItemBlock: check block.properties first
        if (item instanceof ItemBlock) {
            final ItemBlock itemBlock = (ItemBlock) item;
            final Block block = itemBlock.getBlock();

            if (block != null) {
                if (BlockRenderingSettings.INSTANCE.getBlockMetaMatches() != null) {
                    int id = BlockRenderingSettings.INSTANCE.getBlockStateId(block, itemBlock.getMetadata(metadata));
                    if (id != -1) {
                        return id;
                    }
                }
            }
        }

        NbtConditionalIdMap<NamespacedId> itemNbtMap = BlockRenderingSettings.INSTANCE.getItemNbtMap();
        if (itemNbtMap != null) {
            ResourceLocation itemId = Item.REGISTRY.getNameForObject(item);
            if (itemId != null) {
                NamespacedId key = new NamespacedId(itemId.getNamespace(), itemId.getPath());
                if (itemNbtMap.hasConditions(key)) {
                    ItemStack probe = new ItemStack(item, 1, metadata);
                    NBTTagCompound nbt = probe.serializeNBT();
                    int nbtId = itemNbtMap.resolve(key, nbt);
                    if (nbtId != -1) {
                        return nbtId;
                    }
                }
            }
        }

        // Fall back to item.properties
        Object2IntFunction<NamespacedId> itemIds = BlockRenderingSettings.INSTANCE.getItemIds();
        if (itemIds != null) {
            ResourceLocation itemId = Item.REGISTRY.getNameForObject(item);
            if (itemId != null) {
                return itemIds.getInt(new NamespacedId(itemId.getNamespace(), itemId.getPath()));
            }
        }

        return -1;
    }

    /**
     * Clear the material ID cache.
     * Should be called when shader packs are reloaded
     */
    public static void clearCache() {
        MATERIAL_CACHE.clear();
    }
}
