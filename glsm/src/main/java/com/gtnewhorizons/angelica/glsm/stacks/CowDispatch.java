package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.ClipPlaneBooleanState;
import com.gtnewhorizons.angelica.glsm.states.TexEnvState;

@SuppressWarnings("RedundantCast")
public final class CowDispatch {
    public static final byte BLEND = 0;
    public static final byte BOOLEAN = 1;
    public static final byte CLIP_PLANE_BOOLEAN = 2;
    public static final byte TEXTURE_UNIT_BOOLEAN = 3;
    public static final byte COLOR_MASK = 4;
    public static final byte LIGHT_STATE = 5;
    public static final byte MATERIAL = 6;
    public static final byte STENCIL = 7;
    public static final byte MATRIX_MODE = 8;
    public static final byte LIGHT_MODEL = 9;
    public static final byte ALPHA = 10;
    public static final byte POINT = 11;
    public static final byte FOG = 12;
    public static final byte VEC4F = 13;
    public static final byte VEC3F = 14;
    public static final byte INTEGER = 15;
    public static final byte DEPTH = 16;
    public static final byte VIEWPORT = 17;
    public static final byte POLYGON = 18;
    public static final byte COLOR4 = 19;
    public static final byte LINE = 20;
    public static final byte TEXTURE_BINDING = 21;
    public static final byte TEX_ENV = 22;

    private CowDispatch() {}

    public static byte kindOf(CowStateStack<?> stack) {
        final Class<?> type = stack.getClass();
        if (type == TextureUnitBooleanStateStack.class) return TEXTURE_UNIT_BOOLEAN;
        if (type == ClipPlaneBooleanState.class) return CLIP_PLANE_BOOLEAN;
        if (type == BooleanStateStack.class) return BOOLEAN;
        if (type == BlendStateStack.class) return BLEND;
        if (type == ColorMaskStack.class) return COLOR_MASK;
        if (type == LightStateStack.class) return LIGHT_STATE;
        if (type == MaterialStateStack.class) return MATERIAL;
        if (type == StencilStateStack.class) return STENCIL;
        if (type == MatrixModeStack.class) return MATRIX_MODE;
        if (type == LightModelStateStack.class) return LIGHT_MODEL;
        if (type == AlphaStateStack.class) return ALPHA;
        if (type == PointStateStack.class) return POINT;
        if (type == FogStateStack.class) return FOG;
        if (type == Vec4fStack.class) return VEC4F;
        if (type == Vec3fStack.class) return VEC3F;
        if (type == IntegerStateStack.class) return INTEGER;
        if (type == DepthStateStack.class) return DEPTH;
        if (type == ViewPortStateStack.class) return VIEWPORT;
        if (type == PolygonStateStack.class) return POLYGON;
        if (type == Color4Stack.class) return COLOR4;
        if (type == LineStateStack.class) return LINE;
        if (type == TextureBindingStack.class) return TEXTURE_BINDING;
        if (type == TexEnvState.class) return TEX_ENV;
        throw new IllegalStateException("Unknown CowStateStack class: " + type.getName());
    }

    public static boolean topSlotChanged(byte kind, CowStateStack<?> stack) {
        return switch (kind) {
            case BLEND -> ((BlendStateStack) stack).topSlotChanged();
            case BOOLEAN -> ((BooleanStateStack) stack).topSlotChanged();
            case CLIP_PLANE_BOOLEAN -> ((ClipPlaneBooleanState) stack).topSlotChanged();
            case TEXTURE_UNIT_BOOLEAN -> ((TextureUnitBooleanStateStack) stack).topSlotChanged();
            case COLOR_MASK -> ((ColorMaskStack) stack).topSlotChanged();
            case LIGHT_STATE -> ((LightStateStack) stack).topSlotChanged();
            case MATERIAL -> ((MaterialStateStack) stack).topSlotChanged();
            case STENCIL -> ((StencilStateStack) stack).topSlotChanged();
            case MATRIX_MODE -> ((MatrixModeStack) stack).topSlotChanged();
            case LIGHT_MODEL -> ((LightModelStateStack) stack).topSlotChanged();
            case ALPHA -> ((AlphaStateStack) stack).topSlotChanged();
            case POINT -> ((PointStateStack) stack).topSlotChanged();
            case FOG -> ((FogStateStack) stack).topSlotChanged();
            case VEC4F -> ((Vec4fStack) stack).topSlotChanged();
            case VEC3F -> ((Vec3fStack) stack).topSlotChanged();
            case INTEGER -> ((IntegerStateStack) stack).topSlotChanged();
            case DEPTH -> ((DepthStateStack) stack).topSlotChanged();
            case VIEWPORT -> ((ViewPortStateStack) stack).topSlotChanged();
            case POLYGON -> ((PolygonStateStack) stack).topSlotChanged();
            case COLOR4 -> ((Color4Stack) stack).topSlotChanged();
            case LINE -> ((LineStateStack) stack).topSlotChanged();
            case TEXTURE_BINDING -> ((TextureBindingStack) stack).topSlotChanged();
            case TEX_ENV -> ((TexEnvState) stack).topSlotChanged();
            default -> throw new IllegalStateException("Unknown kind: " + kind);
        };
    }

    public static void restoreSlot(byte kind, CowStateStack<?> stack, int slot) {
        switch (kind) {
            case BLEND -> ((BlendStateStack) stack).restoreSlot(slot);
            case BOOLEAN -> ((BooleanStateStack) stack).restoreSlot(slot);
            case CLIP_PLANE_BOOLEAN -> ((ClipPlaneBooleanState) stack).restoreSlot(slot);
            case TEXTURE_UNIT_BOOLEAN -> ((TextureUnitBooleanStateStack) stack).restoreSlot(slot);
            case COLOR_MASK -> ((ColorMaskStack) stack).restoreSlot(slot);
            case LIGHT_STATE -> ((LightStateStack) stack).restoreSlot(slot);
            case MATERIAL -> ((MaterialStateStack) stack).restoreSlot(slot);
            case STENCIL -> ((StencilStateStack) stack).restoreSlot(slot);
            case MATRIX_MODE -> ((MatrixModeStack) stack).restoreSlot(slot);
            case LIGHT_MODEL -> ((LightModelStateStack) stack).restoreSlot(slot);
            case ALPHA -> ((AlphaStateStack) stack).restoreSlot(slot);
            case POINT -> ((PointStateStack) stack).restoreSlot(slot);
            case FOG -> ((FogStateStack) stack).restoreSlot(slot);
            case VEC4F -> ((Vec4fStack) stack).restoreSlot(slot);
            case VEC3F -> ((Vec3fStack) stack).restoreSlot(slot);
            case INTEGER -> ((IntegerStateStack) stack).restoreSlot(slot);
            case DEPTH -> ((DepthStateStack) stack).restoreSlot(slot);
            case VIEWPORT -> ((ViewPortStateStack) stack).restoreSlot(slot);
            case POLYGON -> ((PolygonStateStack) stack).restoreSlot(slot);
            case COLOR4 -> ((Color4Stack) stack).restoreSlot(slot);
            case LINE -> ((LineStateStack) stack).restoreSlot(slot);
            case TEXTURE_BINDING -> ((TextureBindingStack) stack).restoreSlot(slot);
            case TEX_ENV -> ((TexEnvState) stack).restoreSlot(slot);
            default -> throw new IllegalStateException("Unknown kind: " + kind);
        }
    }
}
