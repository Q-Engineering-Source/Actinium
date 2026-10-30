package net.coderbot.iris.block_rendering;

import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import lombok.Getter;
import lombok.Setter;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public class BlockRenderingSettings {
	public static final BlockRenderingSettings INSTANCE = new BlockRenderingSettings();
	public static final int CACHE_MISS = Integer.MIN_VALUE;

	private static final int NBT_CACHE_INTERVAL_TICKS = 20;
	private static final int TE_NBT_CACHE_MAX = 8192;
	private static final Object TE_NBT_CACHE_LOCK = new Object();
	private static final Map<Long, Long> TE_NBT_ID_CACHE = new LinkedHashMap<Long, Long>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
			return size() > TE_NBT_CACHE_MAX;
		}
	};

	@Getter
    private boolean reloadRequired;
	private Reference2ObjectMap<Block, Int2IntMap> blockMetaMatches;
	private NbtConditionalIdMap<Block> blockNbtMap;
	private Map<Block, BlockRenderLayer> blockTypeIds;
    // note: no reload needed, entities are rebuilt every frame.
    @Setter
    private Object2IntFunction<NamespacedId> entityIds;
    @Setter
    private NbtConditionalIdMap<NamespacedId> entityNbtMap;
    // note: no reload needed, items are rendered every frame.
    @Setter
    private Object2IntFunction<NamespacedId> itemIds;
    @Setter
    private NbtConditionalIdMap<NamespacedId> itemNbtMap;
	@Getter
    private float ambientOcclusionLevel;
	private boolean disableDirectionalShading;
	private boolean useSeparateAo;
	private boolean useExtendedVertexFormat;
	@Setter
	private boolean hasSnowyEntries;
	@Getter
	private ReferenceSet<Block> snowyBlocks = new ReferenceOpenHashSet<>();

	public BlockRenderingSettings() {
		reloadRequired = false;
		blockMetaMatches = null;
		blockNbtMap = null;
		blockTypeIds = null;
		ambientOcclusionLevel = 1.0F;
		disableDirectionalShading = false;
		useSeparateAo = false;
		useExtendedVertexFormat = false;
		hasSnowyEntries = false;
	}

	public void clearReloadRequired() {
		reloadRequired = false;
	}

	/** Marks renderer resources for recreation when an external shader layout changes. */
	public void requestRendererReload() {
		this.reloadRequired = true;
	}

	public void reloadRendererIfRequired() {
		if (isReloadRequired()) {
			if (Minecraft.getMinecraft().renderGlobal != null) {
				Minecraft.getMinecraft().renderGlobal.loadRenderers();
			}
			clearReloadRequired();
		}
	}

    @Nullable
	public Reference2ObjectMap<Block, Int2IntMap> getBlockMetaMatches() {
		return blockMetaMatches;
	}

	@Nullable
	public Map<Block, BlockRenderLayer> getBlockTypeIds() {
		return blockTypeIds;
	}

	@Nullable
	public NbtConditionalIdMap<Block> getBlockNbtMap() {
		return blockNbtMap;
	}

	public int getBlockStateId(Block block, int metadata) {
		if (blockMetaMatches != null) {
			Int2IntMap intMap = blockMetaMatches.get(block);
			if (intMap != null) {
				return BlockMaterialMapping.resolveId(intMap, metadata);
			}
		}

		return -1;
	}

	public int resolveBlockNbtId(Block block, @Nullable TileEntity tileEntity) {
		if (blockNbtMap == null || tileEntity == null || !blockNbtMap.hasConditions(block)) {
			return -1;
		}

		BlockPos pos = tileEntity.getPos();
		long packedPos = packBlockPos(pos.getX(), pos.getY(), pos.getZ());
		long currentTick = tileEntity.getWorld() != null ? tileEntity.getWorld().getTotalWorldTime() : 0L;
		int cachedId = getCachedTeNbtId(packedPos, currentTick);
		if (cachedId != CACHE_MISS) {
			return cachedId;
		}

		NBTTagCompound nbt = new NBTTagCompound();
		tileEntity.writeToNBT(nbt);
		int resolvedId = blockNbtMap.resolve(block, nbt);
		cacheTeNbtId(packedPos, resolvedId, currentTick);
		return resolvedId;
	}

	public boolean hasSnowyEntries() {
		return hasSnowyEntries;
	}

	@Nullable
	public Object2IntFunction<NamespacedId> getEntityIds() {
		return entityIds;
	}

	@Nullable
	public NbtConditionalIdMap<NamespacedId> getEntityNbtMap() {
		return entityNbtMap;
	}

	@Nullable
	public Object2IntFunction<NamespacedId> getItemIds() {
		return itemIds;
	}

	@Nullable
	public NbtConditionalIdMap<NamespacedId> getItemNbtMap() {
		return itemNbtMap;
	}

	public void setBlockMetaMatches(Reference2ObjectMap<Block, Int2IntMap> blockMetaIds) {
		this.reloadRequired = true;
		this.blockMetaMatches = blockMetaIds;
	}

	public void setBlockNbtMap(@Nullable NbtConditionalIdMap<Block> blockNbtMap) {
		this.reloadRequired = true;
		this.blockNbtMap = blockNbtMap;
		clearTeNbtCache();
	}

	public void setSnowyBlocks(ReferenceSet<Block> snowyBlocks) {
		this.snowyBlocks = snowyBlocks != null ? snowyBlocks : new ReferenceOpenHashSet<>();
	}

	public void setBlockTypeIds(Map<Block, BlockRenderLayer> blockTypeIds) {
		if (this.blockTypeIds != null && this.blockTypeIds.equals(blockTypeIds)) {
			return;
		}

		this.reloadRequired = true;
		this.blockTypeIds = blockTypeIds;
	}

    public void setAmbientOcclusionLevel(float ambientOcclusionLevel) {
		if (ambientOcclusionLevel == this.ambientOcclusionLevel) {
			return;
		}

		this.reloadRequired = true;
		this.ambientOcclusionLevel = ambientOcclusionLevel;
	}

	public boolean shouldDisableDirectionalShading() {
		return disableDirectionalShading;
	}

	public void setDisableDirectionalShading(boolean disableDirectionalShading) {
		if (disableDirectionalShading == this.disableDirectionalShading) {
			return;
		}

		this.reloadRequired = true;
		this.disableDirectionalShading = disableDirectionalShading;
	}

	public boolean shouldUseSeparateAo() {
		return useSeparateAo;
	}

	public void setUseSeparateAo(boolean useSeparateAo) {
		if (useSeparateAo == this.useSeparateAo) {
			return;
		}

		this.reloadRequired = true;
		this.useSeparateAo = useSeparateAo;
	}

	public boolean shouldUseExtendedVertexFormat() {
		return useExtendedVertexFormat;
	}

	public void setUseExtendedVertexFormat(boolean useExtendedVertexFormat) {
		if (useExtendedVertexFormat == this.useExtendedVertexFormat) {
			return;
		}

		this.reloadRequired = true;
		this.useExtendedVertexFormat = useExtendedVertexFormat;
	}

	public static long packBlockPos(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
	}

	public static void clearTeNbtCache() {
		synchronized (TE_NBT_CACHE_LOCK) {
			TE_NBT_ID_CACHE.clear();
		}
	}

	private static int getCachedTeNbtId(long packedPos, long currentTick) {
		synchronized (TE_NBT_CACHE_LOCK) {
			Long entry = TE_NBT_ID_CACHE.get(packedPos);
			if (entry == null) {
				return CACHE_MISS;
			}

			long entryTick = entry >>> 32;
			if (currentTick - entryTick >= NBT_CACHE_INTERVAL_TICKS) {
				TE_NBT_ID_CACHE.remove(packedPos);
				return CACHE_MISS;
			}

			return (int) (long) entry;
		}
	}

	private static void cacheTeNbtId(long packedPos, int shaderId, long currentTick) {
		long packedValue = ((currentTick & 0x7FFFFFFFL) << 32) | (shaderId & 0xFFFFFFFFL);
		synchronized (TE_NBT_CACHE_LOCK) {
			TE_NBT_ID_CACHE.put(packedPos, packedValue);
		}
	}
}
