package net.coderbot.iris.uniforms;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.coderbot.iris.block_rendering.BlockMaterialMapping;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapturedRenderingStateTest {
    private static final float EPSILON = 1.0e-6f;

    private final CapturedRenderingState state = CapturedRenderingState.INSTANCE;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @BeforeEach
    void resetCapturedState() {
        this.state.setStateRecorder(null);
        this.state.setCurrentEntityAndItem(-1, -1);
        this.state.setCurrentBlockEntity(0);
        this.state.setCurrentEntityColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @AfterEach
    void resetBlockEntity() {
        BlockRenderingSettings.INSTANCE.setBlockMetaMatches(null);
        ItemMaterialHelper.clearCache();
        this.state.setStateRecorder(null);
        this.state.setCurrentBlockEntity(0);
    }

    @Test
    void blockEntityScopeRestoresTheOuterId() {
        state.setCurrentBlockEntity(7);
        state.pushCurrentBlockEntity();
        state.setCurrentBlockEntity(19);

        state.popCurrentBlockEntity();
        assertEquals(7, state.getCurrentRenderedBlockEntity());

        state.pushCurrentBlockEntity();
        state.setCurrentBlockEntity(23);
        state.popCurrentBlockEntity();
        assertEquals(7, state.getCurrentRenderedBlockEntity());
    }

    @Test
    void unbalancedPopFailsImmediately() {
        assertThrows(NoSuchElementException.class, state::popCurrentBlockEntity);
    }

    @Test
    void entityAndItemListenersOnlyFireForValuesThatChanged() {
        int[] entityNotifications = new int[1];
        int[] itemNotifications = new int[1];
        int[] mergedNotifications = new int[1];
        this.state.getEntityIdNotifier().setListener(() -> entityNotifications[0]++);
        this.state.getItemIdNotifier().setListener(() -> itemNotifications[0]++);
        this.state.setEntityIdsListener(() -> mergedNotifications[0]++);
        mergedNotifications[0] = 0;

        this.state.setCurrentEntityAndItem(4, 9);

        assertEquals(1, entityNotifications[0]);
        assertEquals(1, itemNotifications[0]);
        assertEquals(1, mergedNotifications[0]);

        this.state.setCurrentEntityAndItem(4, 9);

        assertEquals(1, entityNotifications[0]);
        assertEquals(1, itemNotifications[0]);
        assertEquals(1, mergedNotifications[0]);

        this.state.setCurrentEntityAndItem(5, 9);

        assertEquals(2, entityNotifications[0]);
        assertEquals(1, itemNotifications[0]);
        assertEquals(2, mergedNotifications[0]);
    }

    @Test
    void entityColorNotifiesOnlyWhenTheColorChanges() {
        int[] notifications = new int[1];
        this.state.getEntityColorNotifier().setListener(() -> notifications[0]++);

        this.state.setCurrentEntityColor(1.0f, 0.5f, 0.25f, 0.75f);
        this.state.setCurrentEntityColor(1.0f, 0.5f, 0.25f, 0.75f);

        assertEquals(1, notifications[0]);
    }

    @Test
    void entityStateRestoresEntityItemBlockEntityAndColor() {
        this.state.setCurrentEntityAndItem(11, 12);
        this.state.setCurrentBlockEntity(13);
        this.state.setCurrentEntityColor(0.1f, 0.2f, 0.3f, 0.4f);

        this.state.pushEntityState();
        this.state.setCurrentEntityAndItem(21, 22);
        this.state.setCurrentBlockEntity(23);
        this.state.setCurrentEntityColor(0.9f, 0.8f, 0.7f, 0.6f);
        this.state.popEntityState();

        assertEquals(11, this.state.getCurrentRenderedEntity());
        assertEquals(12, this.state.getCurrentRenderedItem());
        assertEquals(13, this.state.getCurrentRenderedBlockEntity());
        assertEquals(0.1f, this.state.getCurrentEntityColor().x, EPSILON);
        assertEquals(0.2f, this.state.getCurrentEntityColor().y, EPSILON);
        assertEquals(0.3f, this.state.getCurrentEntityColor().z, EPSILON);
        assertEquals(0.4f, this.state.getCurrentEntityColor().w, EPSILON);
    }

    @Test
    void nestedItemScopesRestoreTheItemSeenByTheCaller() {
        this.state.setCurrentRenderedItem(31);

        this.state.pushCurrentEntityAndItem();
        this.state.setCurrentEntityAndItem(41, 42);
        this.state.pushCurrentRenderedItem();
        this.state.setCurrentRenderedItem(43);
        this.state.popCurrentRenderedItem();

        assertEquals(42, this.state.getCurrentRenderedItem());

        this.state.popCurrentEntityAndItem();

        assertEquals(-1, this.state.getCurrentRenderedEntity());
        assertEquals(31, this.state.getCurrentRenderedItem());
    }

    @Test
    void blockMaterialIdsResolveThroughTheBlockPropertiesMapping() {
        Int2ObjectMap<List<BlockEntry>> blockProperties = new Int2ObjectOpenHashMap<>();
        blockProperties.put(7, List.of(BlockEntry.parse("minecraft:stone")));
        BlockRenderingSettings.INSTANCE.setBlockMetaMatches(
            BlockMaterialMapping.createBlockIdMaps(blockProperties, false).blockMetaMap());

        this.state.setCurrentRenderedBlockItem(Blocks.STONE, 0);
        assertEquals(7, this.state.getCurrentRenderedItem());

        this.state.setCurrentRenderedItem(Item.getItemFromBlock(Blocks.STONE), 0);
        assertEquals(7, this.state.getCurrentRenderedItem());
    }

    @Test
    void recorderSeesEveryEntityStateTransition() {
        RecordingStateRecorder recorder = new RecordingStateRecorder();
        this.state.setStateRecorder(recorder);

        this.state.setCurrentEntityAndItem(3, 4);
        this.state.pushEntityState();
        this.state.setCurrentRenderedItem(5);
        this.state.popEntityState();

        assertEquals(List.of(
            "entityAndItem(3,4)",
            "entityScope(true)",
            "blockEntityScope(true)",
            "entityColorScope(true)",
            "itemId(5)",
            "entityColorScope(false)",
            "blockEntityScope(false)",
            "entityScope(false)"
        ), recorder.events);
    }

    @Test
    void withoutARecorderOnlyTheLiveStateChanges() {
        this.state.setCurrentEntityAndItem(6, 8);
        this.state.pushCurrentRenderedItem();
        this.state.popCurrentRenderedItem();

        assertEquals(6, this.state.getCurrentRenderedEntity());
        assertEquals(8, this.state.getCurrentRenderedItem());
        assertNull(this.state.getStateRecorder());
    }
}
