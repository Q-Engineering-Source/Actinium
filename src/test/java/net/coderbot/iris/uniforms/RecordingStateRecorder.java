package net.coderbot.iris.uniforms;

import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Records every render-state event a display list would replay, in emission order. */
public final class RecordingStateRecorder implements CapturedRenderingState.StateRecorder {
    public final List<String> events = new ArrayList<>();

    @Override
    public void recordSpecialCondition(SpecialCondition condition, boolean begin) {
        this.events.add("specialCondition(" + condition + "," + begin + ")");
    }

    @Override
    public void recordOverridePhase(WorldRenderingPhase phase) {
        this.events.add("overridePhase(" + phase + ")");
    }

    @Override
    public void recordPhaseScope(WorldRenderingPhase phase, boolean begin) {
        this.events.add("phaseScope(" + phase + "," + begin + ")");
    }

    @Override
    public void recordNestedEntityScope(boolean begin) {
        this.events.add("nestedEntityScope(" + begin + ")");
    }

    @Override
    public void recordTranslucency(Boolean translucent) {
        this.events.add("translucency(" + translucent + ")");
    }

    @Override
    public void recordTranslucencyScope(Boolean translucent, boolean begin) {
        this.events.add("translucencyScope(" + translucent + "," + begin + ")");
    }

    @Override
    public void recordEntityAndItem(int entity, int item) {
        this.events.add("entityAndItem(" + entity + "," + item + ")");
    }

    @Override
    public void recordNamedEntity(NamespacedId entity) {
        this.events.add("namedEntity(" + entity + ")");
    }

    @Override
    public void recordEntity(Entity entity) {
        this.events.add("entity(" + entity + ")");
    }

    @Override
    public void recordEntityScope(boolean push) {
        this.events.add("entityScope(" + push + ")");
    }

    @Override
    public void recordEntityColor(float r, float g, float b, float a) {
        this.events.add("entityColor(" + r + "," + g + "," + b + "," + a + ")");
    }

    @Override
    public void recordEntityColorScope(boolean push) {
        this.events.add("entityColorScope(" + push + ")");
    }

    @Override
    public void recordItemId(int itemId) {
        this.events.add("itemId(" + itemId + ")");
    }

    @Override
    public void recordItem(ItemStack item) {
        this.events.add("item(" + item + ")");
    }

    @Override
    public void recordItem(Item item, int metadata) {
        this.events.add("itemType(" + item + "," + metadata + ")");
    }

    @Override
    public void recordNamedItem(NamespacedId item) {
        this.events.add("namedItem(" + item + ")");
    }

    @Override
    public void recordBlockItem(Block block, int metadata) {
        this.events.add("blockItem(" + block + "," + metadata + ")");
    }

    @Override
    public void recordItemScope(boolean push) {
        this.events.add("itemScope(" + push + ")");
    }

    @Override
    public void recordBlockEntity(int id) {
        this.events.add("blockEntity(" + id + ")");
    }

    @Override
    public void recordBlockEntity(Block block, int metadata) {
        this.events.add("blockEntityType(" + block + "," + metadata + ")");
    }

    @Override
    public void recordBlockEntityScope(boolean push) {
        this.events.add("blockEntityScope(" + push + ")");
    }
}
