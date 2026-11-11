package com.gtnewhorizons.angelica.sdlgpu.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;

public final class SpvImageFormatMap {
    private SpvImageFormatMap() {}

    public static int glInternalFormat(int spvImageFormat) {
        return switch (spvImageFormat) {
            case Spv.SpvImageFormatRgba32f -> GL30.GL_RGBA32F;
            case Spv.SpvImageFormatRgba16f -> GL30.GL_RGBA16F;
            case Spv.SpvImageFormatR32f -> GL30.GL_R32F;
            case Spv.SpvImageFormatRgba8 -> GL11.GL_RGBA8;
            case Spv.SpvImageFormatRgba8Snorm -> GL31.GL_RGBA8_SNORM;
            case Spv.SpvImageFormatRg32f -> GL30.GL_RG32F;
            case Spv.SpvImageFormatRg16f -> GL30.GL_RG16F;
            case Spv.SpvImageFormatR11fG11fB10f -> GL30.GL_R11F_G11F_B10F;
            case Spv.SpvImageFormatR16f -> GL30.GL_R16F;
            case Spv.SpvImageFormatRgba16 -> GL11.GL_RGBA16;
            case Spv.SpvImageFormatRgb10A2 -> GL11.GL_RGB10_A2;
            case Spv.SpvImageFormatRg16 -> GL30.GL_RG16;
            case Spv.SpvImageFormatRg8 -> GL30.GL_RG8;
            case Spv.SpvImageFormatR16 -> GL30.GL_R16;
            case Spv.SpvImageFormatR8 -> GL30.GL_R8;
            case Spv.SpvImageFormatRgba16Snorm -> GL31.GL_RGBA16_SNORM;
            case Spv.SpvImageFormatRg16Snorm -> GL31.GL_RG16_SNORM;
            case Spv.SpvImageFormatRg8Snorm -> GL31.GL_RG8_SNORM;
            case Spv.SpvImageFormatR16Snorm -> GL31.GL_R16_SNORM;
            case Spv.SpvImageFormatR8Snorm -> GL31.GL_R8_SNORM;
            case Spv.SpvImageFormatRgba32i -> GL30.GL_RGBA32I;
            case Spv.SpvImageFormatRgba16i -> GL30.GL_RGBA16I;
            case Spv.SpvImageFormatRgba8i -> GL30.GL_RGBA8I;
            case Spv.SpvImageFormatR32i -> GL30.GL_R32I;
            case Spv.SpvImageFormatRg32i -> GL30.GL_RG32I;
            case Spv.SpvImageFormatRg16i -> GL30.GL_RG16I;
            case Spv.SpvImageFormatRg8i -> GL30.GL_RG8I;
            case Spv.SpvImageFormatR16i -> GL30.GL_R16I;
            case Spv.SpvImageFormatR8i -> GL30.GL_R8I;
            case Spv.SpvImageFormatRgba32ui -> GL30.GL_RGBA32UI;
            case Spv.SpvImageFormatRgba16ui -> GL30.GL_RGBA16UI;
            case Spv.SpvImageFormatRgba8ui -> GL30.GL_RGBA8UI;
            case Spv.SpvImageFormatR32ui -> GL30.GL_R32UI;
            case Spv.SpvImageFormatRgb10a2ui -> GL33.GL_RGB10_A2UI;
            case Spv.SpvImageFormatRg32ui -> GL30.GL_RG32UI;
            case Spv.SpvImageFormatRg16ui -> GL30.GL_RG16UI;
            case Spv.SpvImageFormatRg8ui -> GL30.GL_RG8UI;
            case Spv.SpvImageFormatR16ui -> GL30.GL_R16UI;
            case Spv.SpvImageFormatR8ui -> GL30.GL_R8UI;
            default -> 0;
        };
    }

    public static int glTextureTarget(int spvDim, boolean arrayed) {
        return switch (spvDim) {
            case Spv.SpvDim3D -> GL12.GL_TEXTURE_3D;
            case Spv.SpvDimCube -> GL13.GL_TEXTURE_CUBE_MAP;
            default -> arrayed ? GL30.GL_TEXTURE_2D_ARRAY : GL11.GL_TEXTURE_2D;
        };
    }

    public static int glFormatForSampledType(int spvBaseType) {
        return switch (spvBaseType) {
            case Spvc.SPVC_BASETYPE_UINT32 -> GL30.GL_R32UI;
            case Spvc.SPVC_BASETYPE_INT32 -> GL30.GL_R32I;
            default -> GL11.GL_RGBA8;
        };
    }
}
