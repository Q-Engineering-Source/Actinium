package com.gtnewhorizons.angelica.config;

/**
 * Core profile versions the client may request, plus the resolution rule that snaps a requested
 * version down to a supported one. {@link #AUTO} hands the probe back to the descending scan over
 * the platform ceiling.
 */
public enum GLVersionChoice {
    AUTO(0),
    GL33(33),
    GL40(40),
    GL41(41),
    GL42(42),
    GL43(43),
    GL44(44),
    GL45(45),
    GL46(46);

    private final int version;

    GLVersionChoice(int version) {
        this.version = version;
    }

    public int version() { return version; }

    public String label() {
        return version / 10 + "." + version % 10;
    }

    public static GLVersionChoice of(int version, int ceiling) {
        GLVersionChoice best = AUTO;
        for (final GLVersionChoice choice : values()) {
            if (choice.version != 0 && choice.version <= version && choice.version <= ceiling) best = choice;
        }
        return best;
    }
}
