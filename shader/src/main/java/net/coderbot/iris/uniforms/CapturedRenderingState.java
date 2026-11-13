package net.coderbot.iris.uniforms;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lombok.Getter;
import lombok.Setter;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.minecraft.block.Block;

import org.joml.Vector4f;

public class CapturedRenderingState {
	public static final CapturedRenderingState INSTANCE = new CapturedRenderingState();

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

    @Getter
    private final Vector4f currentEntityColor = new Vector4f();
    private Runnable entityColorListener = null;

	private CapturedRenderingState() {
	}

    public void setCurrentBlockEntity(int entity) {
		this.currentRenderedBlockEntity = entity;

		if (this.blockEntityIdListener != null) {
			this.blockEntityIdListener.run();
		}
	}

    public void setCurrentBlockEntity(Block block, int metadata) {
        final Int2IntMap ids = BlockRenderingSettings.INSTANCE.getBlockMetaMatches() == null
            ? null : BlockRenderingSettings.INSTANCE.getBlockMetaMatches().get(block);
        setCurrentBlockEntity(ids == null ? 0 : Math.max(0, ids.get(metadata)));
    }

    public void pushCurrentBlockEntity() {
        blockEntityStack.push(currentRenderedBlockEntity);
    }

    public void popCurrentBlockEntity() {
        setCurrentBlockEntity(blockEntityStack.popInt());
    }

    public void setCurrentEntity(int entity) {
		this.currentRenderedEntity = entity;

		if (this.entityIdListener != null) {
			this.entityIdListener.run();
		}
        setCurrentRenderedItem(0);
	}

    public void setCurrentEntityColor(float r, float g, float b, float a) {
        this.currentEntityColor.set(r, g, b, a);

        if (this.entityColorListener != null) {
            this.entityColorListener.run();
        }
    }

	public void setCurrentRenderedItem(int item) {
		if (this.currentRenderedItem == item) {
			return;
		}

		this.currentRenderedItem = item;

		if (this.itemIdListener != null) {
			this.itemIdListener.run();
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
