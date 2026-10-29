package net.coderbot.iris.gl.state;

import com.gtnewhorizons.angelica.client.rendering.TextureTracker;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamicStateNotifierTest {
    @AfterEach
    void removeListeners() {
        StateUpdateNotifiers.bindTextureNotifier.setListener(null);
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        state.getEntityIdNotifier().setListener(null);
        state.getBlockEntityIdNotifier().setListener(null);
        state.getItemIdNotifier().setListener(null);
        state.getEntityColorNotifier().setListener(null);
    }

    @Test
    void textureChangesNotifyEveryRegisteredUniform() {
        TextureTracker tracker = TextureTracker.INSTANCE;
        AtomicInteger atlasUpdates = new AtomicInteger();
        AtomicInteger textureIdUpdates = new AtomicInteger();
        AtomicInteger textureSizeUpdates = new AtomicInteger();
        StateUpdateNotifiers.bindTextureNotifier.setListener(atlasUpdates::incrementAndGet);
        StateUpdateNotifiers.bindTextureNotifier.setListener(textureIdUpdates::incrementAndGet);
        StateUpdateNotifiers.bindTextureNotifier.setListener(textureSizeUpdates::incrementAndGet);

        tracker.onBindTexture();

        assertEquals(1, atlasUpdates.get());
        assertEquals(1, textureIdUpdates.get());
        assertEquals(1, textureSizeUpdates.get());
    }

    @Test
    void capturedStateNotifiersAreStableAndNotifyAllConsumers() {
        // Initialize the texture notifier as well for shared cleanup.
        TextureTracker tracker = TextureTracker.INSTANCE;
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        AtomicInteger entities = new AtomicInteger();
        AtomicInteger blockEntities = new AtomicInteger();
        AtomicInteger items = new AtomicInteger();
        AtomicInteger colors = new AtomicInteger();
        registerTwo(state.getEntityIdNotifier(), entities);
        registerTwo(state.getBlockEntityIdNotifier(), blockEntities);
        registerTwo(state.getItemIdNotifier(), items);
        registerTwo(state.getEntityColorNotifier(), colors);
        assertSame(state.getEntityIdNotifier(), state.getEntityIdNotifier());
        assertSame(state.getBlockEntityIdNotifier(), state.getBlockEntityIdNotifier());
        assertSame(state.getItemIdNotifier(), state.getItemIdNotifier());
        assertSame(state.getEntityColorNotifier(), state.getEntityColorNotifier());

        state.setCurrentEntity(27);
        state.setCurrentBlockEntity(35);
        int itemBaseline = items.get();
        state.setCurrentRenderedItem(17);
        state.setCurrentEntityColor(0.2f, 0.3f, 0.4f, 0.5f);

        assertEquals(2, entities.get());
        assertEquals(2, blockEntities.get());
        assertEquals(itemBaseline + 2, items.get());
        assertEquals(2, colors.get());
        assertEquals(27, state.getCurrentRenderedEntity());
        assertEquals(35, state.getCurrentRenderedBlockEntity());
        assertEquals(17, state.getCurrentRenderedItem());
    }

    private static void registerTwo(ValueUpdateNotifier notifier, AtomicInteger updates) {
        notifier.setListener(updates::incrementAndGet);
        notifier.setListener(updates::incrementAndGet);
    }
}
