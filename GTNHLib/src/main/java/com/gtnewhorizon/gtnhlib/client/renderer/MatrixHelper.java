package com.gtnewhorizon.gtnhlib.client.renderer;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Matrix helpers shared by the fixed-function emulation and the instanced renderers. */
public final class MatrixHelper {

    private static final Matrix4fc IDENTITY = new Matrix4f();

    private MatrixHelper() {
    }

    public static boolean isIdentity(Matrix4f m) {
        return (m.properties() & Matrix4f.PROPERTY_IDENTITY) != 0 || m.equals(IDENTITY, 1e-6f);
    }

    public static void transformVertex(Matrix4f modelViewProjection, Vector4f dest) {
        modelViewProjection.transform(dest);

        if (dest.w != 1.0f) {
            dest.x /= dest.w;
            dest.y /= dest.w;
            dest.z /= dest.w;
        }
    }

    public static void transformUV(Matrix4f texture, Vector4f dest) {
        texture.transform(dest);

        if (dest.w != 1.0f) {
            dest.x /= dest.w;
            dest.y /= dest.w;
        }
    }

    public static void transformColor(Matrix4f color, Vector4f dest) {
        color.transform(dest);
    }

    public static Matrix3f getNormalMatrix(Matrix4f modelViewMatrix, Matrix3f dest) {
        return dest.set(modelViewMatrix).invert().transpose();
    }

    public static void transformNormal(Matrix3f normalMat, Vector3f dest) {
        normalMat.transform(dest);
        dest.normalize();
    }
}
