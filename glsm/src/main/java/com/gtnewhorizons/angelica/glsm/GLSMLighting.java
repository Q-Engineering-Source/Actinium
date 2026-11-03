package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.stacks.LightStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MaterialStateStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.BYPASS_CACHE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.glMaterialBack;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.glMaterialFront;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.mod;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.putLightingInts;

public final class GLSMLighting {

    private GLSMLighting() {}

    public static void glGetMaterial(int face, int pname, FloatBuffer params) {
        final MaterialStateStack state;
        if (face == GL11.GL_FRONT) {
            state = ctx().frontMaterial;
        } else if (face == GL11.GL_BACK) {
            state = ctx().backMaterial;
        } else {
            throw new RuntimeException("Invalid face parameter specified to glGetMaterial: " + face);
        }

        switch (pname) {
            case GL11.GL_AMBIENT -> state.ambient.get(params.position(), params);
            case GL11.GL_DIFFUSE -> state.diffuse.get(params.position(), params);
            case GL11.GL_SPECULAR -> state.specular.get(params.position(), params);
            case GL11.GL_EMISSION -> state.emission.get(params.position(), params);
            case GL11.GL_SHININESS -> params.put(params.position(), state.shininess);
            case GL11.GL_COLOR_INDEXES -> state.colorIndexes.get(params.position(), params);
            default -> {
            }
        }
    }

    public static void glGetMaterial(int face, int pname, IntBuffer params) {
        final FloatBuffer scratch = ctx().queryScratch;
        scratch.clear();
        glGetMaterial(face, pname, scratch);
        putLightingInts(scratch, pname, true, params);
    }

    public static void glGetLight(int light, int pname, FloatBuffer params) {
        final LightStateStack state = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_AMBIENT -> state.ambient.get(params.position(), params);
            case GL11.GL_DIFFUSE -> state.diffuse.get(params.position(), params);
            case GL11.GL_SPECULAR -> state.specular.get(params.position(), params);
            case GL11.GL_POSITION -> state.position.get(params.position(), params);
            case GL11.GL_SPOT_DIRECTION -> state.spotDirection.get(params.position(), params);
            case GL11.GL_SPOT_EXPONENT -> params.put(params.position(), state.spotExponent);
            case GL11.GL_SPOT_CUTOFF -> params.put(params.position(), state.spotCutoff);
            case GL11.GL_CONSTANT_ATTENUATION -> params.put(params.position(), state.constantAttenuation);
            case GL11.GL_LINEAR_ATTENUATION -> params.put(params.position(), state.linearAttenuation);
            case GL11.GL_QUADRATIC_ATTENUATION -> params.put(params.position(), state.quadraticAttenuation);
            default -> {
            }
        }
    }

    public static void glGetLight(int light, int pname, IntBuffer params) {
        final FloatBuffer scratch = ctx().queryScratch;
        scratch.clear();
        glGetLight(light, pname, scratch);
        putLightingInts(scratch, pname, false, params);
    }

    public static void glMaterial(int face, int pname, FloatBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordMaterial(face, pname, params);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (face == GL11.GL_FRONT) {
            mod(ctx().frontMaterial);
            glMaterialFront(pname, params);
        } else if (face == GL11.GL_BACK) {
            mod(ctx().backMaterial);
            glMaterialBack(pname, params);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            mod(ctx().frontMaterial);
            mod(ctx().backMaterial);
            glMaterialFront(pname, params);
            glMaterialBack(pname, params);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
        ctx().lightingGeneration++;
    }

    public static void glMaterial(int face, int pname, IntBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final float[] floatParams = new float[params.remaining()];
            params.mark();
            for (int i = 0; i < floatParams.length; i++) {
                floatParams[i] = GLStateManager.i2f(params.get());
            }
            params.reset();
            DisplayListManager.recordMaterial(face, pname, FloatBuffer.wrap(floatParams));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (face == GL11.GL_FRONT) {
            mod(ctx().frontMaterial);
            glMaterialFront(pname, params);
        } else if (face == GL11.GL_BACK) {
            mod(ctx().backMaterial);
            glMaterialBack(pname, params);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            mod(ctx().frontMaterial);
            mod(ctx().backMaterial);
            glMaterialFront(pname, params);
            glMaterialBack(pname, params);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
        ctx().lightingGeneration++;
    }

    public static void glMaterialf(int face, int pname, float val) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordMaterialf(face, pname, val);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (pname != GL11.GL_SHININESS) {
            // it is only valid to call glMaterialf for the GL_SHININESS parameter
            return;
        }

        if (face == GL11.GL_FRONT) {
            mod(ctx().frontMaterial).setShininess(val);
        } else if (face == GL11.GL_BACK) {
            mod(ctx().backMaterial).setShininess(val);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            mod(ctx().frontMaterial).setShininess(val);
            mod(ctx().backMaterial).setShininess(val);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
        ctx().lightingGeneration++;
    }

    public static void glLight(int light, int pname, FloatBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLight(light, pname, params);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_AMBIENT -> lightState.setAmbient(params);
            case GL11.GL_DIFFUSE -> lightState.setDiffuse(params);
            case GL11.GL_SPECULAR -> lightState.setSpecular(params);
            case GL11.GL_POSITION -> lightState.setPosition(params);
            case GL11.GL_SPOT_DIRECTION -> lightState.setSpotDirection(params);
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(params);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(params);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(params);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(params);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(params);
            default -> {}
        }
        ctx().lightingGeneration++;
    }

    public static void glLight(int light, int pname, IntBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final float[] floatParams = new float[params.remaining()];
            params.mark();
            for (int i = 0; i < floatParams.length; i++) {
                floatParams[i] = GLStateManager.i2f(params.get());
            }
            params.reset();
            DisplayListManager.recordLight(light, pname, FloatBuffer.wrap(floatParams));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_AMBIENT -> lightState.setAmbient(params);
            case GL11.GL_DIFFUSE -> lightState.setDiffuse(params);
            case GL11.GL_SPECULAR -> lightState.setSpecular(params);
            case GL11.GL_POSITION -> lightState.setPosition(params);
            case GL11.GL_SPOT_DIRECTION -> lightState.setSpotDirection(params);
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(params);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(params);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(params);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(params);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(params);
            default -> {}
        }
        ctx().lightingGeneration++;
    }

    public static void glLightf(int light, int pname, float param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightf(light, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(param);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(param);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(param);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(param);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(param);
            default -> {}
        }
        ctx().lightingGeneration++;
    }

    public static void glLighti(int light, int pname, int param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLighti(light, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(param);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(param);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(param);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(param);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(param);
            default -> {
            }
        }
        ctx().lightingGeneration++;
    }

    public static void glLightModel(int pname, FloatBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightModel(pname, params);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().lightModel);
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_AMBIENT -> ctx().lightModel.setAmbient(params);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(params);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(params);
            default -> {
            }
        }
        ctx().lightingGeneration++;
    }

    public static void glLightModel(int pname, IntBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final float[] floatParams = new float[params.remaining()];
            params.mark();
            for (int i = 0; i < floatParams.length; i++) {
                floatParams[i] = GLStateManager.i2f(params.get());
            }
            params.reset();
            DisplayListManager.recordLightModel(pname, FloatBuffer.wrap(floatParams));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().lightModel);
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_AMBIENT -> ctx().lightModel.setAmbient(params);
            case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> ctx().lightModel.setColorControl(params);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(params);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(params);
            default -> {
            }
        }
        ctx().lightingGeneration++;
    }

    public static void glLightModelf(int pname, float param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightModelf(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }

        if (isCachingEnabled()) {
            mod(ctx().lightModel);
            switch (pname) {
                case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(param);
                case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(param);
                default -> {
                }
            }
            ctx().lightingGeneration++;
        }
    }

    public static void glLightModeli(int pname, int param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightModeli(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }

        if (isCachingEnabled()) {
            mod(ctx().lightModel);
            switch (pname) {
                case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> ctx().lightModel.setColorControl(param);
                case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(param);
                case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(param);
                default -> {
                }
            }
            ctx().lightingGeneration++;
        }
    }

    public static void glColorMaterial(int face, int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordColorMaterial(face, mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().colorMaterialFace.getValue() != face || ctx().colorMaterialParameter.getValue() != mode) {
            if (caching) {
                mod(ctx().colorMaterialFace).setValue(face);
                mod(ctx().colorMaterialParameter).setValue(mode);
                ctx().lightingGeneration++;
            }
        }
    }
}
