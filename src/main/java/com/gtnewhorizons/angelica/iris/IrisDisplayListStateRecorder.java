package com.gtnewhorizons.angelica.iris;

import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Feeds every render-state transition into {@link IrisDisplayListState} so a compiled display list
 * replays the same entity, item, block entity, colour and pass state next to its geometry.
 *
 * <p>Installed once at startup and left installed: each recording method checks whether a list is
 * being recorded, so the recorder costs one branch per state change and nothing per frame.</p>
 */
public final class IrisDisplayListStateRecorder implements CapturedRenderingState.StateRecorder {

    private static final IrisDisplayListStateRecorder INSTANCE = new IrisDisplayListStateRecorder();

    private IrisDisplayListStateRecorder() {}

    /** Installs the shared recorder; safe to call more than once. */
    public static void install() {
        CapturedRenderingState.INSTANCE.setStateRecorder(INSTANCE);
    }

    @Override
    public void recordSpecialCondition(SpecialCondition condition, boolean begin) {
        IrisDisplayListState.recordSpecialCondition(condition, begin);
    }

    @Override
    public void recordOverridePhase(WorldRenderingPhase phase) {
        IrisDisplayListState.recordOverridePhase(phase);
    }

    @Override
    public void recordPhaseScope(WorldRenderingPhase phase, boolean begin) {
        IrisDisplayListState.recordPhaseScope(phase, begin);
    }

    @Override
    public void recordNestedEntityScope(boolean begin) {
        IrisDisplayListState.recordNestedEntityScope(begin);
    }

    @Override
    public void recordTranslucency(Boolean translucent) {
        IrisDisplayListState.recordTranslucency(translucent);
    }

    @Override
    public void recordTranslucencyScope(Boolean translucent, boolean begin) {
        IrisDisplayListState.recordTranslucencyScope(translucent, begin);
    }

    @Override
    public void recordEntityAndItem(int entity, int item) {
        IrisDisplayListState.recordEntityAndItem(entity, item);
    }

    @Override
    public void recordNamedEntity(NamespacedId entity) {
        IrisDisplayListState.recordNamedEntity(entity);
    }

    @Override
    public void recordEntity(Entity entity) {
        IrisDisplayListState.recordEntity(entity);
    }

    @Override
    public void recordEntityScope(boolean push) {
        IrisDisplayListState.recordEntityScope(push);
    }

    @Override
    public void recordEntityColor(float r, float g, float b, float a) {
        IrisDisplayListState.recordEntityColor(r, g, b, a);
    }

    @Override
    public void recordEntityColorScope(boolean push) {
        IrisDisplayListState.recordEntityColorScope(push);
    }

    @Override
    public void recordItemId(int itemId) {
        IrisDisplayListState.recordItemId(itemId);
    }

    @Override
    public void recordItem(ItemStack item) {
        IrisDisplayListState.recordItem(item);
    }

    @Override
    public void recordItem(Item item, int metadata) {
        IrisDisplayListState.recordItem(item, metadata);
    }

    @Override
    public void recordNamedItem(NamespacedId item) {
        IrisDisplayListState.recordNamedItem(item);
    }

    @Override
    public void recordBlockItem(Block block, int metadata) {
        IrisDisplayListState.recordBlockItem(block, metadata);
    }

    @Override
    public void recordItemScope(boolean push) {
        IrisDisplayListState.recordItemScope(push);
    }

    @Override
    public void recordBlockEntity(int id) {
        IrisDisplayListState.recordBlockEntity(id);
    }

    @Override
    public void recordBlockEntity(Block block, int metadata) {
        IrisDisplayListState.recordBlockEntity(block, metadata);
    }

    @Override
    public void recordBlockEntityScope(boolean push) {
        IrisDisplayListState.recordBlockEntityScope(push);
    }
}
