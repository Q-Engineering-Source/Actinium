package net.coderbot.iris.uniforms;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import lombok.Getter;
import lombok.Setter;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

/**
 * Tracks the entity, block entity, item and color IDs the shader uniforms expose while geometry
 * is rendered. Every setter updates the live value and forwards the same transition to the
 * installed {@link StateRecorder} so a compiled display list can replay it.
 *
 * <p>Material IDs keep the 1.12.2 representation of the local lookups: an unmapped item ID is
 * {@code -1} (see {@link ItemMaterialHelper}), while an unmapped block or block item is
 * {@code 0}.</p>
 */
public class CapturedRenderingState {
	public static final CapturedRenderingState INSTANCE = new CapturedRenderingState();

	/**
	 * Receives every render-state transition a display list has to replay next to its geometry.
	 *
	 * <p>The host project implements this once and installs it at startup with
	 * {@link CapturedRenderingState#setStateRecorder}; the implementation stays installed and decides per event whether
	 * it has to record, by checking {@code DisplayListManager.isRecording()}. Do not install and
	 * remove a recorder around each draw: the setters already run on every rendered entity, item
	 * and block entity.</p>
	 *
	 * <p>{@link #recordItemId}, {@link #recordEntityAndItem}, {@link #recordBlockEntity(int)} and
	 * {@link #recordEntityColor} are emitted on every setter call and have to be recorded only
	 * when they differ from the tail of the recorded stream. All other events are scoped and have
	 * to be recorded in emission order.</p>
	 */
	public interface StateRecorder {
		/**
		 * Records entering or leaving a special render condition. {@code begin == false} pops the
		 * condition stack, so it has to pair with the begin that pushed it.
		 */
		void recordSpecialCondition(SpecialCondition condition, boolean begin);

		/**
		 * Records an override phase assignment that is not a scope, replacing the current
		 * override phase until the next assignment.
		 */
		void recordOverridePhase(WorldRenderingPhase phase);

		/**
		 * Records a scoped override phase. {@code begin == false} pops the phase stack and
		 * restores the phase the matching begin saw.
		 */
		void recordPhaseScope(WorldRenderingPhase phase, boolean begin);

		/**
		 * Records entering or leaving a nested entity phase. The replay has to keep the boolean
		 * returned by {@code GbufferPrograms.beginNestedEntityPhase} and hand it to
		 * {@code endNestedEntityPhase}: the scope only pushes an override phase when the active
		 * phase was {@code BLOCK_ENTITIES}.
		 */
		void recordNestedEntityScope(boolean begin);

		/**
		 * Records a translucency declaration that is not a scope. {@code null} hands the decision
		 * back to the live blend state.
		 */
		void recordTranslucency(Boolean translucent);

		/**
		 * Records a scoped translucency declaration. The begin call returns the previous
		 * declaration, which the replay has to store and pass to the matching end call.
		 */
		void recordTranslucencyScope(Boolean translucent, boolean begin);

		/**
		 * Records an entity ID and the item ID it implies. Record only when the pair differs from
		 * the tail of the recorded stream.
		 */
		void recordEntityAndItem(int entity, int item);

		/** Records an entity selected by material name; the replay resolves the ID again. */
		void recordNamedEntity(NamespacedId entity);

		/**
		 * Records the entity being rendered. The recorded command has to store
		 * {@code EntityIdHelper.snapshot(entity)} and resolve the ID from that snapshot on replay;
		 * the entity instance outlives neither the frame nor its world.
		 */
		void recordEntity(Entity entity);

		/** Records pushing ({@code true}) or popping ({@code false}) the entity and item scope. */
		void recordEntityScope(boolean push);

		/**
		 * Records the entity color. Record only when the color differs from the tail of the
		 * recorded stream.
		 */
		void recordEntityColor(float r, float g, float b, float a);

		/** Records pushing ({@code true}) or popping ({@code false}) the entity color scope. */
		void recordEntityColorScope(boolean push);

		/**
		 * Records an already resolved item material ID, {@code -1} when the item is unmapped.
		 * Record only when the ID differs from the tail of the recorded stream.
		 */
		void recordItemId(int itemId);

		/**
		 * Records the item stack being rendered. The recorded command has to store a copy of the
		 * stack ({@code item.copy()}, or null for an empty hand); the instance is mutated after the
		 * draw.
		 */
		void recordItem(ItemStack item);

		/**
		 * Records an item type and metadata, e.g. an armor piece rebuilt by the model batches; the
		 * replay resolves the material ID again.
		 */
		void recordItem(Item item, int metadata);

		/** Records an item selected by material name; the replay resolves the ID again. */
		void recordNamedItem(NamespacedId item);

		/**
		 * Records a block rendered outside the terrain pass; the replay resolves the block material
		 * ID again. An unmapped block resolves to {@code 0}.
		 */
		void recordBlockItem(Block block, int metadata);

		/** Records pushing ({@code true}) or popping ({@code false}) the item ID scope. */
		void recordItemScope(boolean push);

		/**
		 * Records an already resolved block entity material ID. Record only when the ID differs from
		 * the tail of the recorded stream.
		 */
		void recordBlockEntity(int id);

		/** Records a block entity type and metadata; the replay resolves the material ID again. */
		void recordBlockEntity(Block block, int metadata);

		/** Records pushing ({@code true}) or popping ({@code false}) the block entity ID scope. */
		void recordBlockEntityScope(boolean push);
	}

	@Nullable
	private StateRecorder stateRecorder;

	@Setter
    @Getter
    private float tickDelta;
	@Getter
    private int currentRenderedBlockEntity;
	private Runnable blockEntityIdListener = null;
	private final IntArrayList blockEntityStack = new IntArrayList();

	@Getter
    private int currentRenderedEntity = -1;
	private Runnable entityIdListener = null;

	@Getter
	private int currentRenderedItem = -1;
	private Runnable itemIdListener = null;
	private Runnable entityIdsListener = null;

    @Getter
    private final Vector4f currentEntityColor = new Vector4f();
    private Runnable entityColorListener = null;

	private final IntArrayList itemStack = new IntArrayList();
	private final IntArrayList entityStack = new IntArrayList();
	private final FloatArrayList entityColorStack = new FloatArrayList();

	private CapturedRenderingState() {
	}

	/**
	 * Installs the recorder that receives every state transition, or detaches it when null. The
	 * host installs it once at startup and keeps it installed; the recorder itself checks whether
	 * a display list is being recorded.
	 */
	public void setStateRecorder(@Nullable StateRecorder recorder) {
		this.stateRecorder = recorder;
	}

	/** Returns the installed recorder, or null when nothing is being recorded. */
	@Nullable
	public StateRecorder getStateRecorder() {
		return this.stateRecorder;
	}

    public void setCurrentBlockEntity(int entity) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordBlockEntity(entity);
		}
		applyCurrentBlockEntity(entity);
	}

    public void setCurrentBlockEntity(Block block, int metadata) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordBlockEntity(block, metadata);
		}
		applyCurrentBlockEntity(resolveBlockEntityId(block, metadata));
    }

	private static int resolveBlockEntityId(Block block, int metadata) {
		final Reference2ObjectMap<Block, Int2IntMap> matches = BlockRenderingSettings.INSTANCE.getBlockMetaMatches();
		final Int2IntMap ids = matches == null ? null : matches.get(block);
		return ids == null ? 0 : Math.max(0, ids.get(metadata));
	}

    public void pushCurrentBlockEntity() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordBlockEntityScope(true);
		}
        blockEntityStack.push(currentRenderedBlockEntity);
    }

    public void popCurrentBlockEntity() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordBlockEntityScope(false);
		}
        applyCurrentBlockEntity(blockEntityStack.popInt());
    }

	private void applyCurrentBlockEntity(int entity) {
		if (this.currentRenderedBlockEntity == entity) {
			return;
		}

		this.currentRenderedBlockEntity = entity;

		if (this.blockEntityIdListener != null) {
			this.blockEntityIdListener.run();
		}
		notifyEntityIdsChanged();
	}

	/** Sets the entity ID and clears the item ID, keeping both uniforms in sync. */
    public void setCurrentEntity(int entity) {
		setCurrentEntityAndItem(entity, 0);
	}

	public void setCurrentEntityAndItem(int entity, int item) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntityAndItem(entity, item);
		}
		applyCurrentEntityAndItem(entity, item);
	}

	public void setCurrentRenderedEntity(Entity entity) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntity(entity);
		}
		applyCurrentEntityAndItem(EntityIdHelper.getEntityId(entity), 0);
	}

	public void setCurrentNamedEntity(NamespacedId entity) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordNamedEntity(entity);
		}
		final Object2IntFunction<NamespacedId> ids = BlockRenderingSettings.INSTANCE.getEntityIds();
		applyCurrentEntityAndItem(ids == null ? -1 : ids.applyAsInt(entity), 0);
	}

	public void pushCurrentEntityAndItem() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntityScope(true);
		}
		entityStack.push(currentRenderedEntity);
		itemStack.push(currentRenderedItem);
	}

	public void popCurrentEntityAndItem() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntityScope(false);
		}
		applyCurrentEntityAndItem(entityStack.popInt(), itemStack.popInt());
	}

	private void applyCurrentEntityAndItem(int entity, int item) {
		final boolean entityChanged = this.currentRenderedEntity != entity;
		final boolean itemChanged = this.currentRenderedItem != item;

		if (entityChanged) {
			this.currentRenderedEntity = entity;

			if (this.entityIdListener != null) {
				this.entityIdListener.run();
			}
		}

		if (itemChanged) {
			this.currentRenderedItem = item;

			if (this.itemIdListener != null) {
				this.itemIdListener.run();
			}
		}

		if (entityChanged || itemChanged) {
			notifyEntityIdsChanged();
		}
	}

    public void setCurrentEntityColor(float r, float g, float b, float a) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntityColor(r, g, b, a);
		}
		applyCurrentEntityColor(r, g, b, a);
    }

	public void pushCurrentEntityColor() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntityColorScope(true);
		}
		entityColorStack.push(currentEntityColor.x);
		entityColorStack.push(currentEntityColor.y);
		entityColorStack.push(currentEntityColor.z);
		entityColorStack.push(currentEntityColor.w);
	}

	public void popCurrentEntityColor() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordEntityColorScope(false);
		}
		final float a = entityColorStack.popFloat();
		final float b = entityColorStack.popFloat();
		final float g = entityColorStack.popFloat();
		final float r = entityColorStack.popFloat();
		applyCurrentEntityColor(r, g, b, a);
	}

	private void applyCurrentEntityColor(float r, float g, float b, float a) {
		if (currentEntityColor.equals(r, g, b, a)) {
			return;
		}

		this.currentEntityColor.set(r, g, b, a);

		if (this.entityColorListener != null) {
			this.entityColorListener.run();
		}
	}

	/** Pushes the entity, block entity and color scopes a nested renderer has to restore. */
	public void pushEntityState() {
		pushCurrentEntityAndItem();
		pushCurrentBlockEntity();
		pushCurrentEntityColor();
	}

	public void popEntityState() {
		popCurrentEntityColor();
		popCurrentBlockEntity();
		popCurrentEntityAndItem();
	}

	public void setCurrentRenderedItem(int item) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordItemId(item);
		}
		applyCurrentRenderedItem(item);
	}

	public void pushCurrentRenderedItem() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordItemScope(true);
		}
		itemStack.push(currentRenderedItem);
	}

	/** Sets the material ID of the given stack, {@code -1} when the item is unmapped. */
	public void setCurrentRenderedItem(ItemStack item) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordItem(item);
		}
		applyCurrentRenderedItem(ItemMaterialHelper.getMaterialId(item));
	}

	public void setCurrentRenderedItem(Item item, int metadata) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordItem(item, metadata);
		}
		applyCurrentRenderedItem(ItemMaterialHelper.getMaterialId(item, metadata));
	}

	public void setCurrentNamedItem(NamespacedId item) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordNamedItem(item);
		}
		final Object2IntFunction<NamespacedId> ids = BlockRenderingSettings.INSTANCE.getItemIds();
		applyCurrentRenderedItem(ids == null ? 0 : ids.applyAsInt(item));
	}

	/** Sets the material ID of a block rendered outside the terrain pass, {@code 0} when unmapped. */
	public void setCurrentRenderedBlockItem(Block block, int metadata) {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordBlockItem(block, metadata);
		}
		final int materialId = BlockRenderingSettings.INSTANCE.getBlockStateId(block, metadata);
		applyCurrentRenderedItem(materialId < 0 ? 0 : materialId);
	}

	public void popCurrentRenderedItem() {
		final StateRecorder recorder = this.stateRecorder;
		if (recorder != null) {
			recorder.recordItemScope(false);
		}
		applyCurrentRenderedItem(itemStack.popInt());
	}

	private void applyCurrentRenderedItem(int item) {
		if (this.currentRenderedItem == item) {
			return;
		}

		this.currentRenderedItem = item;

		if (this.itemIdListener != null) {
			this.itemIdListener.run();
		}
		notifyEntityIdsChanged();
	}

	/** Registers the listener told about every entity, block entity or item id change, and primes it once. */
	public void setEntityIdsListener(Runnable listener) {
		this.entityIdsListener = listener;
		notifyEntityIdsChanged();
	}

	private void notifyEntityIdsChanged() {
		if (this.entityIdsListener != null) {
			this.entityIdsListener.run();
		}
	}

	public ValueUpdateNotifier getEntityIdNotifier() {
		return listener -> this.entityIdListener = listener;
	}

	public ValueUpdateNotifier getBlockEntityIdNotifier() {
		return listener -> this.blockEntityIdListener = listener;
	}

	public ValueUpdateNotifier getItemIdNotifier() {
		return listener -> this.itemIdListener = listener;
	}

    public ValueUpdateNotifier getEntityColorNotifier() { return listener -> this.entityColorListener = listener; }
}
