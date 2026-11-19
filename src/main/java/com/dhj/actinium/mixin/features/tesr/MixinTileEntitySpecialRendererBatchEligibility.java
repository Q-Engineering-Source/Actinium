package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.mixins.interfaces.IBatchEligibility;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the tile entity renderer's batching verdict, which {@code BatchEligibility} learns from draws. */
@Mixin(TileEntitySpecialRenderer.class)
public abstract class MixinTileEntitySpecialRendererBatchEligibility implements IBatchEligibility {

    @Unique
    private byte actinium$batchState;

    @Override
    public byte angelica$batchState() {
        return this.actinium$batchState;
    }

    @Override
    public void angelica$setBatchState(byte state) {
        this.actinium$batchState = state;
    }
}
