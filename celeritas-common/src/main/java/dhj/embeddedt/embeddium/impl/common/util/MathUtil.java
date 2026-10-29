package dhj.embeddedt.embeddium.impl.common.util;

public class MathUtil {
    /**
     * @return True if the specified number is greater than zero and is a power of two, otherwise false
     */
    public static boolean isPowerOfTwo(int n) {
        return ((n & (n - 1)) == 0);
    }

    /** {@return the greatest value which divides both arguments, used to derive a shared arena stride} */
    public static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }

        return a;
    }

    /** {@return the smallest value which both arguments divide, used to derive a shared arena stride} */
    public static int lcm(int a, int b) {
        return a / gcd(a, b) * b;
    }

    public static long toMib(long bytes) {
        return bytes / (1024L * 1024L); // 1 MiB = 1048576 (2^20) bytes
    }

    public static int roundToward(int value, int factor) {
        return -Math.floorDiv(-value, factor) * factor;
    }

    public static float square(float f) {
        return f * f;
    }

    public static int mojfloor(float f) {
        int truncated = (int)f;
        return f < (float)truncated ? truncated - 1 : truncated;
    }

    public static int mojfloor(double f) {
        int truncated = (int)f;
        return f < (double)truncated ? truncated - 1 : truncated;
    }

    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public static boolean roughlyEqual(float a, float b) {
        return Math.abs(b - a) < 0.00001f;
    }
}
