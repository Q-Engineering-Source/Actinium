package com.dhj.actinium.mixin.core.vertex;

import com.dhj.actinium.render.vertex.FastVertexLayout;
import com.dhj.actinium.render.vertex.FastVertexLayoutCalculator;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Maintains the pre-computed element offsets and the element-advance ring on every vertex
 * format for the immediate-mode write path. {@code addElement} (the only append path; the
 * copy constructor and format constants route through it) and {@code clear} are both
 * covered so the arrays can never diverge from the element list.
 */
@Mixin(VertexFormat.class)
public abstract class MixinVertexFormat implements FastVertexLayout {
    @Shadow
    private List<VertexFormatElement> elements;

    @Unique
    private int[] actinium$offsets = new int[0];

    @Unique
    private int[] actinium$nextIndices = new int[0];

    @Inject(method = "addElement", at = @At("TAIL"))
    private void actinium$rebuildLayout(VertexFormatElement element,
                                        CallbackInfoReturnable<VertexFormat> cir) {
        FastVertexLayoutCalculator.Layout layout =
            FastVertexLayoutCalculator.compute(this.elements);
        this.actinium$offsets = layout.offsets();
        this.actinium$nextIndices = layout.nextIndices();
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void actinium$clearLayout(CallbackInfo ci) {
        this.actinium$offsets = new int[0];
        this.actinium$nextIndices = new int[0];
    }

    @Override
    public int[] actinium$offsets() {
        return this.actinium$offsets;
    }

    @Override
    public int[] actinium$nextIndices() {
        return this.actinium$nextIndices;
    }
}
