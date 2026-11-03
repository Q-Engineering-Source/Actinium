package net.irisshaders.iris.api.v0.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemPiston;
import net.minecraft.item.ItemStack;

/** Supplies the light emission value used for Iris shader-pack handheld lighting. */
public interface IrisItemLightProvider {
    /**
     * Returns the held item's block emission, or zero when the item is not a block item.
     *
     * <p>The emission comes from the block's default state. Deriving a state from the stack metadata is
     * not possible here: {@code Item.getMetadata} reports the metadata written when the item is placed in
     * the world, and item subclasses such as {@link ItemPiston} return constants that no block state
     * accepts.</p>
     *
     * @param player player holding the item, available for item-specific implementations
     * @param stack held item stack resolved to the default state of its block
     * @return block light emission in Minecraft's standard 0-15 range
     */
    @SuppressWarnings("deprecation") // Minecraft 1.12.2 only exposes the emission through this legacy accessor.
    default int getLightEmission(@SuppressWarnings("unused") EntityPlayer player, ItemStack stack) {
        if (stack.getItem() instanceof ItemBlock itemBlock) {
            return itemBlock.getBlock().getDefaultState().getLightValue();
        }

        return 0;
    }
}
