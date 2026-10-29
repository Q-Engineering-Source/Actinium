package net.coderbot.iris.uniforms;

import lombok.Getter;
import lombok.Setter;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.state.MultiListenerNotifier;

import org.joml.Vector4f;

public class CapturedRenderingState {
	public static final CapturedRenderingState INSTANCE = new CapturedRenderingState();

	@Setter
    @Getter
    private float tickDelta;
	@Getter
    private int currentRenderedBlockEntity;
	private final MultiListenerNotifier blockEntityIdListener = new MultiListenerNotifier();

	@Getter
    private int currentRenderedEntity = -1;
	private final MultiListenerNotifier entityIdListener = new MultiListenerNotifier();

	@Getter
	private int currentRenderedItem = -1;
	private final MultiListenerNotifier itemIdListener = new MultiListenerNotifier();

    @Getter
    private final Vector4f currentEntityColor = new Vector4f();
    private final MultiListenerNotifier entityColorListener = new MultiListenerNotifier();

	private CapturedRenderingState() {
	}

    public void setCurrentBlockEntity(int entity) {
		this.currentRenderedBlockEntity = entity;

		this.blockEntityIdListener.run();
	}

    public void setCurrentEntity(int entity) {
		this.currentRenderedEntity = entity;

		this.entityIdListener.run();
        setCurrentRenderedItem(0);
	}

    public void setCurrentEntityColor(float r, float g, float b, float a) {
        this.currentEntityColor.set(r, g, b, a);

        this.entityColorListener.run();
    }

	public void setCurrentRenderedItem(int item) {
		if (this.currentRenderedItem == item) {
			return;
		}

		this.currentRenderedItem = item;

		this.itemIdListener.run();
	}

	public ValueUpdateNotifier getEntityIdNotifier() {
		return this.entityIdListener;
	}

	public ValueUpdateNotifier getBlockEntityIdNotifier() {
		return this.blockEntityIdListener;
	}

	public ValueUpdateNotifier getItemIdNotifier() {
		return this.itemIdListener;
	}

    public ValueUpdateNotifier getEntityColorNotifier() { return this.entityColorListener; }
}
