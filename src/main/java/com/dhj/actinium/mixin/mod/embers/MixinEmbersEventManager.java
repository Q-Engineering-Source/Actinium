package com.dhj.actinium.mixin.mod.embers;

import com.dhj.actinium.compat.embers.EmbersTileEntityIteration;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(targets = "teamroots.embers.EventManager", remap = false)
public abstract class MixinEmbersEventManager {
    @ModifyExpressionValue(
            method = "onRenderAfterWorld(Lnet/minecraftforge/client/event/RenderWorldLastEvent;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/WorldClient;loadedTileEntityList:Ljava/util/List;", remap = true),
            allow = 1
    )
    private List<TileEntity> actinium$snapshotLoadedTileEntities(List<TileEntity> loaded) {
        return EmbersTileEntityIteration.snapshot(loaded);
    }
}
