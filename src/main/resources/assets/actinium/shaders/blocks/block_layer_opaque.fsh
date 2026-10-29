#version 330 core

#import <actinium:include/fog.glsl>

in vec4 v_Color;
in vec2 v_TexCoord;
#ifdef USE_BILINEAR_CORRECTION
in vec4 v_RdhFactor;
in vec2 v_QuadCoord;
#endif

#if defined(USE_FOG) && defined(CHUNK_FADE_IN_DURATION_MS) && CHUNK_FADE_IN_DURATION_MS > 0
in float v_ChunkAgeMs;
#endif

#ifdef USE_FRAGMENT_DISCARD
in float v_MaterialAlphaCutoff;
#endif

// Negative for layers that do not use mipmaps, zero otherwise. Every sampling path below folds it into the
// level it selects, so the per-layer mip behaviour survives the shared terrain program.
in float v_MaterialMipBias;

#if defined(USE_FOG_POSTMODERN)
in float v_SphericalFragDistance;
in float v_CylindricalFragDistance;
#elif defined(USE_FOG)
in float v_FragDistance;
#endif

uniform sampler2D u_BlockTex; // The block texture

uniform int u_FogShape;
uniform vec4 u_FogColor; // The color of the shader fog

#ifdef USE_FOG_SMOOTH
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog
#endif

#ifdef USE_FOG_POSTMODERN
uniform float u_RenderDistFogStart;
uniform float u_RenderDistFogEnd;
uniform float u_EnvFogStart;
uniform float u_EnvFogEnd;
#endif

#if defined(USE_FOG_EXP) || defined(USE_FOG_EXP2)
uniform float u_FogDensity; // The density of the shader fog
#endif

#ifndef LEGACY
out vec4 fragColor; // The output fragment for the color framebuffer
#else
#define fragColor gl_FragColor
#endif

#ifndef LEGACY

/**
 * Snaps the sample point to a texel centre, with the transition spread over the width of one screen pixel in
 * texels so a texel boundary does not flicker while a texel is still roughly one pixel wide.
 */
vec2 snapToTexelCentre(vec2 uv, vec2 texelSize, vec2 texelsPerPixel) {
    vec2 centreCoords = uv / texelSize - 0.5;
    vec2 lowerCentre = floor(centreCoords);
    vec2 alongSeam = centreCoords - lowerCentre;

    vec2 rampWidth = clamp(texelsPerPixel, vec2(1e-8), vec2(1.0));
    alongSeam = clamp((alongSeam - 0.5) / rampWidth + 0.5, 0.0, 1.0);

    return (lowerCentre + alongSeam + 0.5) * texelSize;
}

vec2 texelsPerPixelOf(vec2 du, vec2 dv, vec2 texelSize) {
    return sqrt(du * du + dv * dv) / texelSize;
}

#ifdef USE_ANISOTROPIC
/**
 * Shrinks the sampling footprint to what the atlas gutter can absorb. Anisotropic taps reach past the
 * sprite's own texels, and the replicated border is only TERRAIN_GUTTER texels wide, so an unbounded
 * footprint would pull in the neighbouring sprite.
 */
float footprintScale(vec2 du, vec2 dv, vec2 texelSize, float limitTexels) {
    float footprint = max(length(du / texelSize), length(dv / texelSize));
    return min(1.0, limitTexels / max(footprint, 1e-8));
}
#endif

vec4 sampleTexelSnapped(sampler2D tex, vec2 uv, vec2 texelSize, vec2 du, vec2 dv, vec2 texelsPerPixel) {
    vec2 snapped = snapToTexelCentre(uv, texelSize, texelsPerPixel);

#if defined(TERRAIN_NO_MIPS) && !defined(USE_ANISOTROPIC)
    // A single-level atlas has nothing to select, and deriving a level from the derivatives would read an
    // incomplete texture.
    return textureLod(tex, snapped, 0.0);
#else
    float gradientScale = exp2(v_MaterialMipBias);
#ifdef USE_ANISOTROPIC
    gradientScale *= footprintScale(du * gradientScale, dv * gradientScale, texelSize, TERRAIN_GUTTER);
#endif

    return textureGrad(tex, snapped, du * gradientScale, dv * gradientScale);
#endif
}

vec4 sampleTexelSnapped(sampler2D tex, vec2 uv, vec2 texelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    return sampleTexelSnapped(tex, uv, texelSize, du, dv, texelsPerPixelOf(du, dv, texelSize));
}

#ifdef USE_RGSS

// How many levels sharper the four taps are sampled, which is what taking four of them buys.
#ifndef RGSS_MIP_BIAS
#define RGSS_MIP_BIAS -1.0
#endif

// Standard rotated-grid supersampling offsets, in texels.
const vec2 RGSS_OFFSETS[4] = vec2[4](
    vec2( 0.125,  0.375),
    vec2(-0.125, -0.375),
    vec2( 0.375, -0.125),
    vec2(-0.375,  0.125)
);

#ifdef USE_ANISOTROPIC
const float RGSS_FOOTPRINT_LIMIT = 0.75 * TERRAIN_GUTTER;
#endif

/**
 * Rotated-grid supersampling, faded in as a texel grows past one screen pixel and back out once the
 * anisotropic footprint would leave the gutter. Outside that window the texel-snapped path is used, so the
 * sprite stays sharp up close and never reads a neighbour at grazing angles.
 */
vec4 sampleRGSS(sampler2D tex, vec2 uv, vec2 texelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelsPerPixel = texelsPerPixelOf(du, dv, texelSize);
    float rgssFactor = smoothstep(1.0, 2.0, max(texelsPerPixel.x, texelsPerPixel.y));

#ifdef USE_ANISOTROPIC
    float footprint = max(length(du / texelSize), length(dv / texelSize));
    rgssFactor *= 1.0 - smoothstep(RGSS_FOOTPRINT_LIMIT, TERRAIN_GUTTER, footprint);
#endif

    if (rgssFactor <= 0.0) {
        return sampleTexelSnapped(tex, uv, texelSize, du, dv, texelsPerPixel);
    }

    vec4 rgss = vec4(0.0);
#ifdef USE_ANISOTROPIC
    float fit = footprintScale(du, dv, texelSize, RGSS_FOOTPRINT_LIMIT);
    vec2 spreadU = du * fit;
    vec2 spreadV = dv * fit;

    // The taps span the footprint; the gradients are one level sharper, which is what four of them buy.
    float gradientScale = exp2(v_MaterialMipBias + RGSS_MIP_BIAS);
    vec2 gradU = spreadU * gradientScale;
    vec2 gradV = spreadV * gradientScale;

    for (int i = 0; i < 4; i++) {
        rgss += textureGrad(tex, uv + RGSS_OFFSETS[i].x * spreadU + RGSS_OFFSETS[i].y * spreadV, gradU, gradV);
    }
#elif defined(TERRAIN_NO_MIPS)
    const float lod = 0.0;

    for (int i = 0; i < 4; i++) {
        rgss += textureLod(tex, uv + RGSS_OFFSETS[i] * texelSize, lod);
    }
#else
    float duLength = length(du / texelSize);
    float dvLength = length(dv / texelSize);
    float lod = max(0.0, 0.5 * log2(duLength * dvLength) + v_MaterialMipBias + RGSS_MIP_BIAS);

    for (int i = 0; i < 4; i++) {
        rgss += textureLod(tex, uv + RGSS_OFFSETS[i] * texelSize, lod);
    }
#endif
    rgss *= 0.25;

    if (rgssFactor >= 1.0) {
        return rgss;
    }

    return mix(sampleTexelSnapped(tex, uv, texelSize, du, dv, texelsPerPixel), rgss, rgssFactor);
}
#endif

#endif

void main() {
#if defined(LEGACY)
    // The GLSL 120 downlevel has no textureLod, so sampling stays two-argument and the GL filter state
    // picks the level.
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord);
#elif defined(USE_TEXEL_SNAP)
    vec2 texelSize = 1.0 / vec2(textureSize(u_BlockTex, 0));
#ifdef USE_RGSS
    vec4 diffuseColor = sampleRGSS(u_BlockTex, v_TexCoord, texelSize);
#else
    vec4 diffuseColor = sampleTexelSnapped(u_BlockTex, v_TexCoord, texelSize);
#endif
#else
    // The texture's own filter state picks the level, which is what a player who turned filtering off and
    // asked for nearest texels gets, and what keeps alpha-test fragments alive at level boundaries. A
    // negative bias means the layer is not mipped: vanilla samples BlockRenderLayer.CUTOUT at level 0, and
    // every cutout layer shares one render pass here, so the material bit selects that level (#191).
    vec4 diffuseColor = v_MaterialMipBias < 0.0
            ? textureLod(u_BlockTex, v_TexCoord, 0.0)
            : texture(u_BlockTex, v_TexCoord);
#endif

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    vec4 m_color = v_Color;
#ifdef USE_BILINEAR_CORRECTION
    // min(x, y) * (1 - max(x, y)) == min(x, y) - (x * y)
    float correctionWeight = min(v_QuadCoord.x, v_QuadCoord.y)
            - (v_QuadCoord.x * v_QuadCoord.y);
    m_color += v_RdhFactor * correctionWeight;
#endif

#ifdef USE_VANILLA_COLOR_FORMAT
    // Apply per-vertex color. AO shade is applied ahead of time on the CPU.
    diffuseColor *= m_color;
#else
    // Apply per-vertex color
    diffuseColor.rgb *= m_color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= m_color.a;
#endif

#ifdef USE_FOG
#if defined(CHUNK_FADE_IN_DURATION_MS) && CHUNK_FADE_IN_DURATION_MS > 0
    // Make chunk fade in over a short duration
    diffuseColor = vec4(mix(u_FogColor.rgb, diffuseColor.rgb, (clamp(v_ChunkAgeMs, 0, CHUNK_FADE_IN_DURATION_MS) / CHUNK_FADE_IN_DURATION_MS)), diffuseColor.a);
#endif

#ifdef USE_FOG_POSTMODERN
    float fogValue = max(_linearFogValue(v_CylindricalFragDistance, u_RenderDistFogStart, u_RenderDistFogEnd),
                         _linearFogValue(v_SphericalFragDistance, u_EnvFogStart, u_EnvFogEnd));

    fragColor = vec4(mix(diffuseColor.rgb, u_FogColor.rgb, fogValue * u_FogColor.a), diffuseColor.a);
#else // Legacy fog
    float fragDistance;
    if (u_FogShape == FOG_SHAPE_PLANAR) {
        fragDistance = gl_FragCoord.z / gl_FragCoord.w;
    } else {
        fragDistance = v_FragDistance;
    }
#if defined(USE_FOG_EXP2)
    fragColor = _exp2Fog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_EXP)
    fragColor = _expFog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_SMOOTH)
    fragColor = _linearFog(diffuseColor, fragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif
#endif

#else // No fog
    fragColor = diffuseColor;
#endif
}
