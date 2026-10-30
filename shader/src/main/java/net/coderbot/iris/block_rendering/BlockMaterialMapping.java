package net.coderbot.iris.block_rendering;

import com.gtnewhorizons.angelica.compat.ModStatus;
import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.coderbot.iris.shaderpack.materialmap.BlockRenderType;
import net.coderbot.iris.shaderpack.materialmap.FlatteningMap;
import net.coderbot.iris.shaderpack.materialmap.LegacyBlockTags;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.coderbot.iris.shaderpack.materialmap.TagEntry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class BlockMaterialMapping {
	public record BlockIdMaps(it.unimi.dsi.fastutil.objects.Reference2ObjectMap<Block, Int2IntMap> blockMetaMap,
	                          NbtConditionalIdMap<Block> blockNbtMap) {
	}

	public static final int SNOWY_META_BIT = 1 << 16;
	public static final int DOUBLE_PLANT_TOP_BIT = 0x8;
	public static final int WILDCARD_META_KEY = 1 << 30;

	/**
	 * Legacy helper kept for older call sites. New code should prefer {@link #createBlockIdMaps(Int2ObjectMap, boolean)}.
	 */
	public static Reference2ObjectMap<Block, Int2IntMap> createBlockMetaIdMap(Int2ObjectMap<List<BlockEntry>> blockPropertiesMap) {
		BlockIdMaps blockIdMaps = createBlockIdMaps(blockPropertiesMap, new Int2ObjectOpenHashMap<>(), false);
		BlockRenderingSettings.INSTANCE.setBlockNbtMap(blockIdMaps.blockNbtMap());
		return blockIdMaps.blockMetaMap();
	}

	/**
	 * Creates the standard block meta ID map, the TileEntity NBT-conditional map, and registers
	 * snowy blocks on {@link BlockRenderingSettings}.
	 */
	public static BlockIdMaps createBlockIdMaps(Int2ObjectMap<List<BlockEntry>> blockPropertiesMap, boolean skipFlattening) {
		return createBlockIdMaps(blockPropertiesMap, new Int2ObjectOpenHashMap<>(), skipFlattening);
	}

	public static BlockIdMaps createBlockIdMaps(Int2ObjectMap<List<BlockEntry>> blockPropertiesMap,
	                                            Int2ObjectMap<List<TagEntry>> blockTagMap,
	                                            boolean skipFlattening) {
		final it.unimi.dsi.fastutil.objects.Reference2ObjectMap<Block, Int2IntMap> blockMatches =
			new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();
		final NbtConditionalIdMap<Block> blockNbtMap = new NbtConditionalIdMap<>();
		final ReferenceSet<Block> snowyBlocks = new ReferenceOpenHashSet<>();

		blockPropertiesMap.forEach((intId, entries) -> {
			for (BlockEntry entry : entries) {
				if (entry.hasNbtProperties()) {
					addTileEntityEntry(entry, blockNbtMap, intId);
				} else {
					addBlockMetas(entry, blockMatches, blockNbtMap, intId, skipFlattening, snowyBlocks);
				}
			}
		});

		blockTagMap.forEach((intId, entries) -> {
			for (TagEntry entry : entries) {
				for (BlockEntry resolved : LegacyBlockTags.resolve(entry)) {
					addBlockMetas(resolved, blockMatches, blockNbtMap, intId, skipFlattening, snowyBlocks);
				}
			}
		});

		BlockRenderingSettings.INSTANCE.setHasSnowyEntries(!snowyBlocks.isEmpty());
		BlockRenderingSettings.INSTANCE.setSnowyBlocks(snowyBlocks);
		return new BlockIdMaps(blockMatches, blockNbtMap);
	}

	public static Map<Block, BlockRenderLayer> createBlockTypeMap(Map<NamespacedId, BlockRenderType> blockPropertiesMap) {
		Map<Block, BlockRenderLayer> blockTypeIds = new Reference2ReferenceOpenHashMap<>();

		blockPropertiesMap.forEach((id, blockType) -> {
			Block block = resolveBlockOrNull(id);

			if (block == null && "minecraft".equals(id.getNamespace())) {
				List<BlockEntry> legacyEntries = FlatteningMap.toLegacy(id.getName(), Map.of());
				if (legacyEntries != null && !legacyEntries.isEmpty()) {
					block = resolveBlockOrNull(legacyEntries.get(0).getId());
				}
			}

			if (block == null) {
				return;
			}

			final BlockRenderLayer layer = convertBlockToRenderLayer(blockType);
			if (layer != null) {
				blockTypeIds.put(block, layer);
			}
		});

		return blockTypeIds;
	}

	public static NbtConditionalIdMap<NamespacedId> createNamespacedNbtMap(Int2ObjectMap<List<BlockEntry>> nbtEntries) {
		NbtConditionalIdMap<NamespacedId> map = new NbtConditionalIdMap<>();

		nbtEntries.forEach((intId, entries) -> {
			for (BlockEntry entry : entries) {
				if (entry.hasNbtProperties()) {
					map.addCondition(entry.getId(), entry.getNbtProperties(), intId);
				}
			}
		});

		return map;
	}

	private static void addTileEntityEntry(BlockEntry entry, NbtConditionalIdMap<Block> blockNbtMap, int intId) {
		Block block = resolveBlockOrNull(entry.getId());
		if (block == null) {
			return;
		}

		blockNbtMap.addCondition(block, entry.getNbtProperties(), intId);
	}

	private static void addBlockMetas(BlockEntry entry,
	                                  it.unimi.dsi.fastutil.objects.Reference2ObjectMap<Block, Int2IntMap> idMap,
	                                  NbtConditionalIdMap<Block> blockNbtMap,
	                                  int intId,
	                                  boolean skipFlattening,
	                                  ReferenceSet<Block> snowyBlocks) {
		final NamespacedId id = entry.getId();
		final Map<String, String> stateProperties = entry.getStateProperties();
		final String snowy = stateProperties.get("snowy");
		final int snowyBit = "true".equals(snowy) ? SNOWY_META_BIT : 0;
		final boolean wantsDoublePlantTop = "upper".equals(stateProperties.get("half"));

		List<BlockEntry> targets = null;
		if (!skipFlattening && "minecraft".equals(id.getNamespace()) && (!stateProperties.isEmpty() || entry.getMetas().isEmpty())) {
			targets = FlatteningMap.toLegacy(id.getName(), stateProperties);
		}

		if (targets == null) {
			targets = List.of(entry);
		}

		for (BlockEntry target : targets) {
			if (target.hasNbtProperties()) {
				addTileEntityEntry(target, blockNbtMap, intId);
				continue;
			}

			Block block = resolveBlockOrNull(target.getId());
			if (block == null) {
				continue;
			}

			int extraBits = snowyBit;
			if (wantsDoublePlantTop && block instanceof BlockDoublePlant) {
				extraBits |= DOUBLE_PLANT_TOP_BIT;
			}

			applyMetas(block, target.getMetas(), idMap, intId, extraBits);
			if (snowy != null) {
				snowyBlocks.add(block);
			}
		}
	}

	private static void applyMetas(Block block,
	                               Set<Integer> metas,
	                               it.unimi.dsi.fastutil.objects.Reference2ObjectMap<Block, Int2IntMap> idMap,
	                               int intId,
	                               int extraBits) {
		Int2IntMap metaMap = idMap.get(block);
		if (metaMap == null) {
			metaMap = new Int2IntOpenHashMap();
			metaMap.defaultReturnValue(-1);
			idMap.put(block, metaMap);
		}

		if (metas.isEmpty()) {
			for (int meta = 0; meta < 16; meta++) {
				metaMap.putIfAbsent(meta | extraBits, intId);
			}
			if (ModStatus.isMetadataExtended) {
				metaMap.putIfAbsent(WILDCARD_META_KEY | extraBits, intId);
			}
		} else {
			for (int meta : metas) {
				metaMap.putIfAbsent(meta | extraBits, intId);
			}
		}
	}

	private static Block resolveBlockOrNull(NamespacedId id) {
		final ResourceLocation resourceLocation = new ResourceLocation(id.getNamespace(), id.getName());
		final Block block = Block.getBlockFromName(resourceLocation.toString());
		return block == null || block == Blocks.AIR ? null : block;
	}

	public static int resolveId(Int2IntMap metaMap, int metaKey) {
		int id = metaMap.get(metaKey);
		return id != -1 ? id : metaMap.get(WILDCARD_META_KEY | (metaKey & SNOWY_META_BIT));
	}

	private static BlockRenderLayer convertBlockToRenderLayer(BlockRenderType type) {
		if (type == null) {
			return null;
		}

		return switch (type) {
			case SOLID -> BlockRenderLayer.SOLID;
			case CUTOUT -> BlockRenderLayer.CUTOUT;
			case CUTOUT_MIPPED -> BlockRenderLayer.CUTOUT_MIPPED;
			case TRANSLUCENT -> BlockRenderLayer.TRANSLUCENT;
		};
	}
}
