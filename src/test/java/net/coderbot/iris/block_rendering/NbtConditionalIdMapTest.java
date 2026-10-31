package net.coderbot.iris.block_rendering;

import net.coderbot.iris.shaderpack.materialmap.PropertiesTokenizer;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NbtConditionalIdMapTest {
    @Test
    void requiresBothMetadataAndNbtAndContinuesToLaterRules() {
        NbtConditionalIdMap<String> map = new NbtConditionalIdMap<>();
        map.addCondition("machine", condition("active"), 100, Set.of(2));
        map.addCondition("machine", condition("active"), 200, Set.of(3));

        assertEquals(100, map.resolve("machine", nbt("active"), 2));
        assertEquals(200, map.resolve("machine", nbt("active"), 3));
        assertEquals(-1, map.resolve("machine", nbt("active"), 4));
        assertEquals(-1, map.resolve("machine", nbt("inactive"), 2));
        assertEquals(-1, map.resolve("unknown", nbt("active"), 2));
    }

    @Test
    void emptyMetadataSetMatchesAnyMetadataAndUnrestrictedRulesKeepTheirOrder() {
        NbtConditionalIdMap<String> map = new NbtConditionalIdMap<>();
        map.addCondition("machine", condition("active"), 100, Set.of(2));
        map.addCondition("machine", condition("active"), 300, Set.of());

        assertEquals(100, map.resolve("machine", nbt("active"), 2));
        assertEquals(300, map.resolve("machine", nbt("active"), 4096));
        assertEquals(-1, map.resolve("machine", nbt("inactive"), 4096));
    }

    @Test
    void entityAndItemNbtOnlyResolutionDoesNotImposeMetadata() {
        NbtConditionalIdMap<String> map = new NbtConditionalIdMap<>();
        map.addCondition("entity", condition("active"), 100, Set.of(2));
        map.addCondition("item", condition("active"), 200);

        assertEquals(100, map.resolve("entity", nbt("active")));
        assertEquals(-1, map.resolve("entity", nbt("inactive")));
        assertEquals(200, map.resolve("item", nbt("active")));
    }

    @Test
    void metadataSetIsCapturedWhenRuleIsRegistered() {
        NbtConditionalIdMap<String> map = new NbtConditionalIdMap<>();
        Set<Integer> metadata = new HashSet<>(Set.of(2));
        map.addCondition("machine", condition("active"), 100, metadata);
        metadata.clear();

        assertEquals(100, map.resolve("machine", nbt("active"), 2));
        assertEquals(-1, map.resolve("machine", nbt("active"), 3));
    }

    private static Map<String, PropertiesTokenizer.NbtValue> condition(String value) {
        return Map.of("mode", new PropertiesTokenizer.NbtValue(value, true));
    }

    private static NBTTagCompound nbt(String value) {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("mode", value);
        return nbt;
    }
}
