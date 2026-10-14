package com.dhj.actinium.mixin.mod.gvclib;

import com.dhj.actinium.compat.gvclib.GvclibHandLightingCompat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraftforge.client.event.RenderHandEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Routes GVCLib's shader hand draw once and scopes lightmap data to its arm model parts. */
@Pseudo
@Mixin(targets = "gvclib.event.gun.GVCEventsGunRender_First", remap = false)
public abstract class MixinGVCFirstPersonArmLighting {
    @WrapMethod(method = "rendergun(Lnet/minecraftforge/client/event/RenderHandEvent;)V")
    private void actinium$renderGunOnce(RenderHandEvent event, Operation<Void> original) {
        boolean shaderPackInUse = GvclibHandLightingCompat.isShaderPackInUse();
        if (!GvclibHandLightingCompat.shouldRenderFirstPersonGun(
            shaderPackInUse,
            GvclibHandLightingCompat.isIrisHandPassActive()
        )) {
            return;
        }

        if (!shaderPackInUse) {
            original.call(event);
            return;
        }

        GvclibHandLightingCompat.beginFirstPersonModelRender();
        try {
            original.call(event);
        } finally {
            GvclibHandLightingCompat.endFirstPersonModelRender();
        }
    }

    @WrapOperation(
        method = "renderarm(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;Lgvclib/item/ItemGunBase;)V",
        at = @At(
            value = "INVOKE",
            target = "Lobjmodel/IModelCustom;renderPart(Ljava/lang/String;)V",
            remap = false
        )
    )
    private void actinium$lightArmModelPart(
        @Coerce Object armModel,
        String partName,
        Operation<Void> original
    ) {
        if (!GvclibHandLightingCompat.isFirstPersonArmPart(partName)) {
            original.call(armModel, partName);
            return;
        }

        GvclibHandLightingCompat.beginFirstPersonModelRender();
        try {
            original.call(armModel, partName);
        } finally {
            GvclibHandLightingCompat.endFirstPersonModelRender();
        }
    }

    @WrapOperation(
        method = "renderarm_reloattest(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;Lgvclib/item/ItemGunBase;)V",
        at = @At(
            value = "INVOKE",
            target = "Lobjmodel/IModelCustom;renderPart(Ljava/lang/String;)V",
            remap = false
        )
    )
    private void actinium$lightReloadTestArmModelPart(
        @Coerce Object armModel,
        String partName,
        Operation<Void> original
    ) {
        if (!GvclibHandLightingCompat.isFirstPersonArmPart(partName)) {
            original.call(armModel, partName);
            return;
        }

        GvclibHandLightingCompat.beginFirstPersonModelRender();
        try {
            original.call(armModel, partName);
        } finally {
            GvclibHandLightingCompat.endFirstPersonModelRender();
        }
    }
}
