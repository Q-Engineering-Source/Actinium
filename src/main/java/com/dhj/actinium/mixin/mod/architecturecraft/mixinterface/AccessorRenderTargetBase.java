package com.dhj.actinium.mixin.mod.architecturecraft.mixinterface;

import com.elytradev.architecture.client.render.target.RenderTargetBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes ArchitectureCraft's face-shade scalar so modern packs can keep AO but disable old directional shade. */
@Mixin(value = RenderTargetBase.class, remap = false)
public interface AccessorRenderTargetBase {
    @Accessor("shade")
    float actinium$getShade();
}
