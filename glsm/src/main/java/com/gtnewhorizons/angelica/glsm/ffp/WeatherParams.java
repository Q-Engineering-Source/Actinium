package com.gtnewhorizons.angelica.glsm.ffp;

/**
 * The per-frame half of the weather instanced draw.
 *
 * <p>Everything that is constant for one weather pass lives here instead of in the instance buffer:
 * the camera-relative translation, the inverse window radius, the camera fraction inside its block,
 * the frame time and the three scroll and strength values. {@link #set} bumps {@link #generation} so
 * a program can tell whether it already uploaded these values.</p>
 */
public final class WeatherParams {

    public static float translateX;
    public static float translateY;
    public static float translateZ;
    public static float invRadius;

    public static float cameraFracX;
    public static float cameraFracZ;
    public static float partialTicks;
    public static float age;

    public static float rainScroll;
    public static float snowScroll;
    public static float rainStrength;

    /** Incremented on every {@link #set}; a program records the generation it uploaded. */
    public static int generation;

    private WeatherParams() {
    }

    /**
     * Stores the parameters of the weather pass about to be drawn.
     *
     * @param translateX   negated camera x, applied to the camera-relative quad position
     * @param translateY   negated camera y
     * @param translateZ   negated camera z
     * @param invRadius    {@code 1 / radius}, scaling the horizontal distance for the fade
     * @param cameraFracX  camera position inside its block on x
     * @param cameraFracZ  camera position inside its block on z
     * @param partialTicks frame interpolation of the render pass
     * @param age          renderer update count plus {@code partialTicks}
     * @param rainScroll   renderer update count for the rain texture scroll
     * @param snowScroll   renderer update count for the snow texture scroll
     * @param rainStrength world rain strength of the frame
     */
    public static void set(float translateX, float translateY, float translateZ, float invRadius,
                           float cameraFracX, float cameraFracZ, float partialTicks, float age,
                           float rainScroll, float snowScroll, float rainStrength) {
        WeatherParams.translateX = translateX;
        WeatherParams.translateY = translateY;
        WeatherParams.translateZ = translateZ;
        WeatherParams.invRadius = invRadius;
        WeatherParams.cameraFracX = cameraFracX;
        WeatherParams.cameraFracZ = cameraFracZ;
        WeatherParams.partialTicks = partialTicks;
        WeatherParams.age = age;
        WeatherParams.rainScroll = rainScroll;
        WeatherParams.snowScroll = snowScroll;
        WeatherParams.rainStrength = rainStrength;
        generation++;
    }
}
