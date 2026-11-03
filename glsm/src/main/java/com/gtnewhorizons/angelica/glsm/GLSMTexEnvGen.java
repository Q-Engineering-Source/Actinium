package com.gtnewhorizons.angelica.glsm;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.handleTexEnvScalar;

public final class GLSMTexEnvGen {

    private GLSMTexEnvGen() {}

    public static void glTexEnvi(int target, int pname, int param) {
        handleTexEnvScalar(target, pname, param);
    }
}
