package net.irisshaders.iris.api.v0.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Supplies the light emission value used for Iris shader-pack handheld lighting. */
public interface IrisItemLightProvider {
    /**
     * Returns the held item's block emission, or zero when the item is not a block item.
     *
     * @param player player holding the item, available for item-specific implementations
     * @param stack held item stack whose metadata selects the block state
     * @return block light emission in Minecraft's standard 0-15 range
     */
    @SuppressWarnings("deprecation") // Minecraft 1.12.2 reconstructs item block states through this legacy method.
    default int getLightEmission(@SuppressWarnings("unused") EntityPlayer player, ItemStack stack) {
        if (stack.getItem() instanceof ItemBlock itemBlock) {
            int metadata = itemBlock.getMetadata(stack.getMetadata());
            return itemBlock.getBlock().getStateFromMeta(metadata).getLightValue();
        }

        return 0;
    }
}
