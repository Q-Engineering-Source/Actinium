package com.dhj.actinium.render.terrain;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.dhj.actinium.mixin.vintage.core.terrain.AccessorActiveRenderInfo;

public class CameraHelper {
    public static Vector3f getThirdPersonOffset() {
        final Vector3f offset = new Vector3f();
        final Matrix4f inverseModelView = new Matrix4f(AccessorActiveRenderInfo.getModelViewMatrix()).invert();
        inverseModelView.transformPosition(offset);
        return offset;
    }
}

