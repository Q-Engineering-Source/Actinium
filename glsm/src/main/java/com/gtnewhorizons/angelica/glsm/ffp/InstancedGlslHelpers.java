package com.gtnewhorizons.angelica.glsm.ffp;

/**
 * GLSL fragments the instanced vertex variants are built from.
 *
 * <p>Only the weather instancing exists in this tree, so only its prologue and attribute
 * declarations live here. The prologue expands one column instance into the world-space corner the
 * ordinary position transform then projects: it computes the billboard half width from the column
 * offset, the texture scroll and snow drift from the cached random values, and the transparency from
 * the distance fade and the world rain strength. The formulas match the vanilla fallback in
 * {@code com.dhj.actinium.render.WeatherRenderer} so toggling instancing cannot change how rain and
 * snow look.</p>
 */
public final class InstancedGlslHelpers {

    /**
     * Weather prologue. Placeholders are replaced by {@link #weatherPrologue}.
     *
     * <p>Two details differ from the upstream Angelica prologue on purpose. The texture scroll is
     * negative here, because 1.12.2 vanilla scrolls the rain and snow texture downwards and the
     * fallback keeps that sign. The packed lightmap is read as {@code (sky, block)}, because the
     * lightmap attribute vanilla feeds the fixed pipeline is ordered that way.</p>
     */
    private static final String WEATHER_PROLOGUE = """
        vec2 {p}wColumn = {span}.xy;
        float {p}wBottom = {span}.z;
        float {p}wTop = {span}.w;
        vec2 {p}wLightmap = vec2({params}.y, {params}.x);
        float {p}wHash = {params}.z;
        bool {p}wSnow = {params}.w > 0.5;
        vec3 {p}wTranslate = {p}WeatherParams0.xyz;
        float {p}wInvRadius = {p}WeatherParams0.w;
        vec2 {p}wCameraFrac = {p}WeatherParams1.xy;
        float {p}wPartialTicks = {p}WeatherParams1.z;
        float {p}wAge = {p}WeatherParams1.w;
        float {p}wRainScroll = {p}WeatherParams2.x;
        float {p}wSnowScroll = {p}WeatherParams2.y;
        float {p}wStrength = {p}WeatherParams2.z;

        vec2 {p}wHalfWidth = vec2(0.0);
        float {p}wColumnLength = length({p}wColumn);
        if ({p}wColumnLength > 0.0) {
            {p}wHalfWidth = vec2(-{p}wColumn.y, {p}wColumn.x) * (0.5 / {p}wColumnLength);
        }
        vec2 {p}wCornerXZ = {p}wColumn + vec2(0.5) + ({corner}.x * 2.0 - 1.0) * {p}wHalfWidth;
        float {p}wCornerY = mix({p}wBottom, {p}wTop, {corner}.y);
        {vertex} = vec4(vec3({p}wCornerXZ.x, {p}wCornerY, {p}wCornerXZ.y) + {p}wTranslate, 1.0);

        float {p}wDistance = length({p}wColumn + vec2(0.5) - {p}wCameraFrac) * {p}wInvRadius;
        float {p}wFade = 1.0 - {p}wDistance * {p}wDistance;
        float {p}wScroll;
        vec2 {p}wDrift;
        float {p}wAlpha;
        if ({p}wSnow) {
            {p}wScroll = -({p}wSnowScroll + {p}wPartialTicks) / 512.0;
            {p}wDrift = vec2({jitter}.x + {p}wAge * 0.01 * {jitter}.y, {jitter}.z + {p}wAge * {jitter}.w * 0.001);
            {p}wAlpha = ({p}wFade * 0.3 + 0.5) * {p}wStrength;
        } else {
            {p}wScroll = -(mod({p}wRainScroll + {p}wHash, 32.0) + {p}wPartialTicks) / 32.0 * (3.0 + {jitter}.x);
            {p}wDrift = vec2(0.0);
            {p}wAlpha = ({p}wFade * 0.5 + 0.5) * {p}wStrength;
        }

        {tex0} = vec4({corner}.x + {p}wDrift.x, {p}wCornerY * 0.25 + {p}wScroll + {p}wDrift.y, 0.0, 1.0);
        {tex1} = vec4({p}wLightmap, 0.0, 1.0);
        {color} = vec4(1.0, 1.0, 1.0, {p}wAlpha);""";

    private InstancedGlslHelpers() {
    }

    /**
     * Returns the weather prologue with its placeholders filled in.
     *
     * @param uniformPrefix prefix of the three weather uniforms, {@code u_}
     * @param attribPrefix  prefix of the three instance attributes, {@code a_}
     * @param corner        expression holding the quad corner, xy in {@code [0,1]}
     * @param vertex        lvalue receiving the world-space corner position
     * @param color        lvalue receiving the vertex colour
     * @param tex0          lvalue receiving the rain or snow texture coordinate
     * @param tex1          lvalue receiving the packed lightmap coordinate
     * @param sep           statement separator, {@code "\n  "} inside a function body
     * @return the prologue source
     */
    public static String weatherPrologue(String uniformPrefix, String attribPrefix, String corner,
                                         String vertex, String color, String tex0, String tex1, String sep) {
        return WEATHER_PROLOGUE
            .replace("{p}", uniformPrefix)
            .replace("{span}", attribPrefix + "InstColumnSpan")
            .replace("{jitter}", attribPrefix + "InstJitter")
            .replace("{params}", attribPrefix + "InstWeatherParams")
            .replace("{corner}", corner)
            .replace("{vertex}", vertex)
            .replace("{color}", color)
            .replace("{tex0}", tex0)
            .replace("{tex1}", tex1)
            .replace("\n", sep);
    }

    /**
     * Returns the per-instance attribute declarations of a variant.
     *
     * @param prefix declaration name prefix, {@code a_}
     * @param kind   instancing kind of the variant
     * @return the attribute declarations, empty for {@link Instancing#NONE}
     */
    public static String[] attributeDecls(String prefix, Instancing kind) {
        return switch (kind) {
            case NONE -> new String[0];
            case WEATHER -> new String[]{
                decl(WeatherInstancedAttribs.LOC_COLUMN_SPAN, "vec4", prefix + "InstColumnSpan"),
                decl(WeatherInstancedAttribs.LOC_JITTER, "vec4", prefix + "InstJitter"),
                decl(WeatherInstancedAttribs.LOC_PARAMS, "vec4", prefix + "InstWeatherParams"),
            };
        };
    }

    private static String decl(int location, String type, String name) {
        return "layout(location = " + location + ") in " + type + " " + name + ";\n";
    }
}
