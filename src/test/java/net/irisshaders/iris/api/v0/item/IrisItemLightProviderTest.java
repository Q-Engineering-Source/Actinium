package net.irisshaders.iris.api.v0.item;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemPiston;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the held item emission lookup behind Iris {@code heldBlockLightValue} uniforms.
 *
 * <p>Pistons are carried by {@link ItemPiston}, whose {@code getMetadata} returns the constant
 * {@code 7}. Rebuilding a block state from that value made {@code BlockPistonBase.getStateFromMeta}
 * resolve a null facing and throw, which crashed the client on the first rendered frame while a
 * piston was held (issue #251).</p>
 */
class IrisItemLightProviderTest {
    private static final IrisItemLightProvider PROVIDER = new IrisItemLightProvider() {
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        // Item.registerItems() links Blocks.PISTON to ItemPiston; Bootstrap.register() drives it.
        Bootstrap.register();
    }

    @Test
    void pistonItemReportsNoEmission() {
        assertEquals(0, PROVIDER.getLightEmission(null, new ItemStack(Blocks.PISTON)));
        assertEquals(0, PROVIDER.getLightEmission(null, new ItemStack(Blocks.STICKY_PISTON)));
    }

    @Test
    void lightSourceBlockItemKeepsItsEmission() {
        assertTrue(PROVIDER.getLightEmission(null, new ItemStack(Blocks.TORCH)) > 0);
    }

    @Test
    void nonBlockItemReportsNoEmission() {
        assertEquals(0, PROVIDER.getLightEmission(null, new ItemStack(Items.STICK)));
    }
}
