package com.gtnewhorizons.angelica.sdlgpu.resource;

import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.EXTTextureSRGB;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL42;

import static org.lwjgl.sdl.SDLGPU.*;

/** Maps OpenGL block-compressed storage to the equivalent SDL GPU format and transfer size. */
public final class CompressedTextureFormats {
    private CompressedTextureFormats() {}

    public static int sdlFormat(int format) {
        return switch (format) {
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT,
                 EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT -> SDL_GPU_TEXTUREFORMAT_BC1_RGBA_UNORM;
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT3_EXT -> SDL_GPU_TEXTUREFORMAT_BC2_RGBA_UNORM;
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT -> SDL_GPU_TEXTUREFORMAT_BC3_RGBA_UNORM;
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_S3TC_DXT1_EXT,
                 EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT -> SDL_GPU_TEXTUREFORMAT_BC1_RGBA_UNORM_SRGB;
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT -> SDL_GPU_TEXTUREFORMAT_BC2_RGBA_UNORM_SRGB;
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT -> SDL_GPU_TEXTUREFORMAT_BC3_RGBA_UNORM_SRGB;
            case GL30.GL_COMPRESSED_RED_RGTC1 -> SDL_GPU_TEXTUREFORMAT_BC4_R_UNORM;
            case GL30.GL_COMPRESSED_RG_RGTC2 -> SDL_GPU_TEXTUREFORMAT_BC5_RG_UNORM;
            case GL42.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT -> SDL_GPU_TEXTUREFORMAT_BC6H_RGB_UFLOAT;
            case GL42.GL_COMPRESSED_RGB_BPTC_SIGNED_FLOAT -> SDL_GPU_TEXTUREFORMAT_BC6H_RGB_FLOAT;
            case GL42.GL_COMPRESSED_RGBA_BPTC_UNORM -> SDL_GPU_TEXTUREFORMAT_BC7_RGBA_UNORM;
            case GL42.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM -> SDL_GPU_TEXTUREFORMAT_BC7_RGBA_UNORM_SRGB;
            default -> SDL_GPU_TEXTUREFORMAT_INVALID;
        };
    }

    public static int blockBytes(int format) {
        return switch (sdlFormat(format)) {
            case SDL_GPU_TEXTUREFORMAT_BC1_RGBA_UNORM, SDL_GPU_TEXTUREFORMAT_BC1_RGBA_UNORM_SRGB,
                 SDL_GPU_TEXTUREFORMAT_BC4_R_UNORM -> 8;
            case SDL_GPU_TEXTUREFORMAT_INVALID -> throw new IllegalArgumentException("Unsupported compressed format 0x" + Integer.toHexString(format));
            default -> 16;
        };
    }

    public static boolean isCompressedSdl(int format) {
        return switch (format) {
            case SDL_GPU_TEXTUREFORMAT_BC1_RGBA_UNORM, SDL_GPU_TEXTUREFORMAT_BC2_RGBA_UNORM,
                 SDL_GPU_TEXTUREFORMAT_BC3_RGBA_UNORM, SDL_GPU_TEXTUREFORMAT_BC4_R_UNORM,
                 SDL_GPU_TEXTUREFORMAT_BC5_RG_UNORM,
                 SDL_GPU_TEXTUREFORMAT_BC6H_RGB_UFLOAT,
                 SDL_GPU_TEXTUREFORMAT_BC6H_RGB_FLOAT, SDL_GPU_TEXTUREFORMAT_BC7_RGBA_UNORM,
                 SDL_GPU_TEXTUREFORMAT_BC1_RGBA_UNORM_SRGB, SDL_GPU_TEXTUREFORMAT_BC2_RGBA_UNORM_SRGB,
                 SDL_GPU_TEXTUREFORMAT_BC3_RGBA_UNORM_SRGB, SDL_GPU_TEXTUREFORMAT_BC7_RGBA_UNORM_SRGB -> true;
            default -> false;
        };
    }

    public static int baseFormat(int format) {
        return switch (format) {
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT,
                 EXTTextureSRGB.GL_COMPRESSED_SRGB_S3TC_DXT1_EXT,
                 GL42.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT, GL42.GL_COMPRESSED_RGB_BPTC_SIGNED_FLOAT -> GL11.GL_RGB;
            case GL30.GL_COMPRESSED_RED_RGTC1 -> GL11.GL_RED;
            case GL30.GL_COMPRESSED_RG_RGTC2 -> GL30.GL_RG;
            default -> GL11.GL_RGBA;
        };
    }

    public static boolean isSrgb(int format) {
        return switch (format) {
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_S3TC_DXT1_EXT,
                 EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT,
                 EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT,
                 EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT,
                 GL42.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM -> true;
            default -> false;
        };
    }

    public static int byteSize(int format, int width, int height, int depth) {
        if (width < 0 || height < 0 || depth < 0) throw new IllegalArgumentException("Negative compressed texture dimensions");
        final long blocks = Math.multiplyExact((width + 3L) / 4, (height + 3L) / 4);
        return Math.toIntExact(Math.multiplyExact(Math.multiplyExact(blocks, depth), blockBytes(format)));
    }

    public static void checkRegion(int x, int y, int width, int height, int textureWidth, int textureHeight) {
        if (x < 0 || y < 0 || width < 0 || height < 0 || x > textureWidth - width || y > textureHeight - height
            || (x & 3) != 0 || (y & 3) != 0
            || ((width & 3) != 0 && x + width != textureWidth)
            || ((height & 3) != 0 && y + height != textureHeight)) {
            throw new IllegalArgumentException("Compressed updates must cover complete blocks or reach the mip boundary");
        }
    }
}
