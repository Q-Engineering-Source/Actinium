package com.gtnewhorizons.angelica.glsm;

import org.lwjgl.opengl.OpenGLException;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.ARRAY_QUERY_FLOAT_SCRATCH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ARRAY_QUERY_SCRATCH_SIZE;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.CLIENT_ATTRIB_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.HAS_MULTIPLE_SET;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_ATTRIB_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_CLIP_PLANES;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_COLOR_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_LEGACY_TEXTURE_UNITS;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_MODELVIEW_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_PROJECTION_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_TEXTURE_COORDS;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.MAX_TEXTURE_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.copyTrackedVector;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.drawFramebufferAttachmentSize;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getActiveTextureUnit;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getActiveTextureUnitForServerState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getBoundTextureForServerState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getFogMode;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getListMode;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.getMatrixStackDepth;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.glGetBooleani_v;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.glIsEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.hasContext;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.hintMode;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.imageBindingOrBackend;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.initConfig;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isCachingEnabled;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.isEnableCap;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.maxPointSize;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.queryDriverVector;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.shouldBypassCache;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.widenFromFloat;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.requireContext;

public final class GLSMQueries {

    private GLSMQueries() {}

    public static boolean glGetBoolean(int pname) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getBoolean(pname);
        }
        if (pname >= GL11.GL_CLIP_PLANE0 && pname < GL11.GL_CLIP_PLANE0 + MAX_CLIP_PLANES) {
            return ctx().clipPlaneStates[pname - GL11.GL_CLIP_PLANE0].isEnabled();
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST -> ctx().alphaTest.isEnabled();
            case GL11.GL_AUTO_NORMAL -> ctx().autoNormalState.isEnabled();
            case GL11.GL_BLEND -> ctx().blendMode.isEnabled();
            case GL11.GL_COLOR_MATERIAL -> ctx().colorMaterial.isEnabled();
            case GL11.GL_COLOR_LOGIC_OP -> ctx().colorLogicOpState.isEnabled();
            case GL14.GL_COLOR_SUM -> ctx().colorSumState.isEnabled();
            case GL11.GL_CULL_FACE -> ctx().cullState.isEnabled();
            case GL11.GL_DEPTH_TEST -> ctx().depthTest.isEnabled();
            case GL11.GL_DEPTH_WRITEMASK -> ctx().depthState.isMaskEnabled();
            case GL11.GL_DITHER -> ctx().ditherState.isEnabled();
            case GL11.GL_FOG -> ctx().fogMode.isEnabled();
            case GL11.GL_INDEX_LOGIC_OP -> ctx().indexLogicOpState.isEnabled();
            case GL11.GL_LIGHTING -> ctx().lightingState.isEnabled();
            case GL11.GL_LIGHT0 -> ctx().lightStates[0].isEnabled();
            case GL11.GL_LIGHT1 -> ctx().lightStates[1].isEnabled();
            case GL11.GL_LIGHT2 -> ctx().lightStates[2].isEnabled();
            case GL11.GL_LIGHT3 -> ctx().lightStates[3].isEnabled();
            case GL11.GL_LIGHT4 -> ctx().lightStates[4].isEnabled();
            case GL11.GL_LIGHT5 -> ctx().lightStates[5].isEnabled();
            case GL11.GL_LIGHT6 -> ctx().lightStates[6].isEnabled();
            case GL11.GL_LIGHT7 -> ctx().lightStates[7].isEnabled();
            case GL11.GL_LINE_SMOOTH -> ctx().lineSmoothState.isEnabled();
            case GL11.GL_LINE_STIPPLE -> ctx().lineStippleState.isEnabled();
            case GL11.GL_MAP1_COLOR_4 -> ctx().map1Color4State.isEnabled();
            case GL11.GL_MAP1_INDEX -> ctx().map1IndexState.isEnabled();
            case GL11.GL_MAP1_NORMAL -> ctx().map1NormalState.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_1 -> ctx().map1TextureCoord1State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_2 -> ctx().map1TextureCoord2State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_3 -> ctx().map1TextureCoord3State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_4 -> ctx().map1TextureCoord4State.isEnabled();
            case GL11.GL_MAP1_VERTEX_3 -> ctx().map1Vertex3State.isEnabled();
            case GL11.GL_MAP1_VERTEX_4 -> ctx().map1Vertex4State.isEnabled();
            case GL11.GL_MAP2_COLOR_4 -> ctx().map2Color4State.isEnabled();
            case GL11.GL_MAP2_INDEX -> ctx().map2IndexState.isEnabled();
            case GL11.GL_MAP2_NORMAL -> ctx().map2NormalState.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_1 -> ctx().map2TextureCoord1State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_2 -> ctx().map2TextureCoord2State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_3 -> ctx().map2TextureCoord3State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_4 -> ctx().map2TextureCoord4State.isEnabled();
            case GL11.GL_MAP2_VERTEX_3 -> ctx().map2Vertex3State.isEnabled();
            case GL11.GL_MAP2_VERTEX_4 -> ctx().map2Vertex4State.isEnabled();
            case GL13.GL_MULTISAMPLE -> ctx().multisampleState.isEnabled();
            case GL11.GL_NORMALIZE -> ctx().normalizeState.isEnabled();
            case GL11.GL_POINT_SMOOTH -> ctx().pointSmoothState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_POINT -> ctx().polygonOffsetPointState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_LINE -> ctx().polygonOffsetLineState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_FILL -> ctx().polygonOffsetFillState.isEnabled();
            case GL11.GL_POLYGON_SMOOTH -> ctx().polygonSmoothState.isEnabled();
            case GL11.GL_POLYGON_STIPPLE -> ctx().polygonStippleState.isEnabled();
            case GL12.GL_RESCALE_NORMAL -> ctx().rescaleNormalState.isEnabled();
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> ctx().sampleAlphaToCoverageState.isEnabled();
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> ctx().sampleAlphaToOneState.isEnabled();
            case GL13.GL_SAMPLE_COVERAGE -> ctx().sampleCoverageState.isEnabled();
            case GL11.GL_SCISSOR_TEST -> ctx().scissorTest.isEnabled();
            case GL11.GL_STENCIL_TEST -> ctx().stencilTest.isEnabled();
            case GL11.GL_TEXTURE_1D -> ctx().textures.getTexture1DStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_2D -> ctx().textures.getTextureUnitStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL12.GL_TEXTURE_3D -> ctx().textures.getTexture3DStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL13.GL_TEXTURE_CUBE_MAP -> ctx().textures.getTextureCubeMapStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_S -> ctx().textures.getTexGenSStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_T -> ctx().textures.getTexGenTStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_R -> ctx().textures.getTexGenRStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_Q -> ctx().textures.getTexGenQStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.localViewer != 0.0f;
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.twoSide != 0.0f;
            case GL20.GL_POINT_SPRITE -> ctx().pointSpriteState.isEnabled();
            case GL20.GL_VERTEX_PROGRAM_TWO_SIDE -> ctx().vertexProgramTwoSideState.isEnabled();
            case GL11.GL_EDGE_FLAG -> ctx().edgeFlagState.isEnabled();
            default -> {
                final int conv = VAOManager.pnameToConventional(pname, ctx().clientActiveTextureUnit);
                if (conv >= 0) yield ctx().vaos.isConventionalEnabled(conv);
                final int desc = LegacyQueries.desc(pname);
                yield desc != 0 ? LegacyQueries.getBoolean(ctx(), pname, desc) : RENDER_BACKEND.getBoolean(pname);
            }
        };
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        if (shouldBypassCache()) {
            RENDER_BACKEND.getBoolean(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_COLOR_WRITEMASK -> {
                final int pos = params.position();
                params.put(pos, (byte) (ctx().colorMask.red ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put(pos + 1, (byte) (ctx().colorMask.green ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put(pos + 2, (byte) (ctx().colorMask.blue ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put(pos + 3, (byte) (ctx().colorMask.alpha ? GL11.GL_TRUE : GL11.GL_FALSE));
            }
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(params.position(), (byte) (glGetBoolean(pname) ? GL11.GL_TRUE : GL11.GL_FALSE));
                } else if (!LegacyQueries.getBooleanv(ctx(), pname, params)) {
                    RENDER_BACKEND.getBoolean(pname, params);
                }
            }
        }
    }

    public static int glGetInteger(int pname) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getInteger(pname);
        }

        if (isEnableCap(pname)) {
            return glIsEnabled(pname) ? GL11.GL_TRUE : GL11.GL_FALSE;
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST_FUNC -> ctx().alphaState.getFunction();
            case GL11.GL_DEPTH_FUNC -> ctx().depthState.getFunc();
            case GL11.GL_DEPTH_WRITEMASK -> ctx().depthState.isMaskEnabled() ? 1 : 0;
            case GL11.GL_LIST_BASE -> ctx().listBase;
            case GL11.GL_LIST_MODE -> DisplayListManager.getListMode();
            case GL11.GL_MATRIX_MODE -> ctx().matrixMode.getMode();
            case GL11.GL_SHADE_MODEL -> ctx().shadeModelState.getValue();
            case GL11.GL_TEXTURE_BINDING_2D -> getBoundTextureForServerState();
            case GL13.GL_ACTIVE_TEXTURE -> GL13.GL_TEXTURE0 + getActiveTextureUnitForServerState();
            case GL11.GL_COLOR_MATERIAL_FACE -> ctx().colorMaterialFace.getValue();
            case GL11.GL_COLOR_MATERIAL_PARAMETER -> ctx().colorMaterialParameter.getValue();
            case GL11.GL_MODELVIEW_STACK_DEPTH -> getMatrixStackDepth(ctx().modelViewMatrix);
            case GL11.GL_PROJECTION_STACK_DEPTH -> getMatrixStackDepth(ctx().projectionMatrix);
            case GL11.GL_TEXTURE_STACK_DEPTH -> getMatrixStackDepth(ctx().textures.getTextureUnitMatrix(getActiveTextureUnit()));
            case ARBImaging.GL_COLOR_MATRIX_STACK_DEPTH -> getMatrixStackDepth(ctx().colorMatrix);
            case ARBImaging.GL_MAX_COLOR_MATRIX_STACK_DEPTH -> MAX_COLOR_STACK_DEPTH;
            case GL11.GL_MAX_MODELVIEW_STACK_DEPTH -> MAX_MODELVIEW_STACK_DEPTH;
            case GL11.GL_MAX_PROJECTION_STACK_DEPTH -> MAX_PROJECTION_STACK_DEPTH;
            case GL11.GL_MAX_TEXTURE_STACK_DEPTH -> MAX_TEXTURE_STACK_DEPTH;
            case GL11.GL_MAX_ATTRIB_STACK_DEPTH -> MAX_ATTRIB_STACK_DEPTH;
            case GL11.GL_MAX_CLIENT_ATTRIB_STACK_DEPTH -> CLIENT_ATTRIB_STACK_DEPTH;
            case GL11.GL_ATTRIB_STACK_DEPTH -> ctx().attribDepth + DisplayListManager.virtualStateDepth(DisplayListManager.getRecordMode());
            case GL11.GL_CLIENT_ATTRIB_STACK_DEPTH -> ctx().clientAttribStackPointer;
            case GL11.GL_MAX_LIGHTS -> ctx().lightStates.length;
            case GL11.GL_MAX_CLIP_PLANES -> MAX_CLIP_PLANES;
            case GL20.GL_MAX_TEXTURE_COORDS -> MAX_TEXTURE_COORDS;
            case GL13.GL_MAX_TEXTURE_UNITS -> MAX_LEGACY_TEXTURE_UNITS;
            case GL13.GL_CLIENT_ACTIVE_TEXTURE -> GL13.GL_TEXTURE0 + ctx().clientActiveTextureUnit;
            case GL11.GL_RENDER_MODE -> FeedbackManager.getRenderMode();
            case GL20.GL_POINT_SPRITE_COORD_ORIGIN -> ctx().pointState.getSpriteCoordOrigin();
            case GL11.GL_FOG_HINT, GL11.GL_PERSPECTIVE_CORRECTION_HINT, GL11.GL_POINT_SMOOTH_HINT,
                 GL11.GL_LINE_SMOOTH_HINT, GL11.GL_POLYGON_SMOOTH_HINT, GL13.GL_TEXTURE_COMPRESSION_HINT,
                 GL14.GL_GENERATE_MIPMAP_HINT, GL20.GL_FRAGMENT_SHADER_DERIVATIVE_HINT -> hintMode(ctx(), pname);
            case GL11.GL_CULL_FACE_MODE -> ctx().polygonState.getCullFaceMode();
            case GL11.GL_FRONT_FACE -> ctx().polygonState.getFrontFace();
            case GL11.GL_POLYGON_MODE -> ctx().polygonState.getFrontMode();
            case GL11.GL_UNPACK_ALIGNMENT -> ctx().pixelUnpackState.alignment();
            case GL11.GL_UNPACK_ROW_LENGTH -> ctx().pixelUnpackState.rowLength();
            case GL11.GL_UNPACK_SKIP_ROWS -> ctx().pixelUnpackState.skipRows();
            case GL11.GL_UNPACK_SKIP_PIXELS -> ctx().pixelUnpackState.skipPixels();
            case GL12.GL_UNPACK_IMAGE_HEIGHT -> ctx().pixelUnpackState.imageHeight();
            case GL12.GL_UNPACK_SKIP_IMAGES -> ctx().pixelUnpackState.skipImages();
            case GL11.GL_UNPACK_SWAP_BYTES -> ctx().pixelUnpackState.swapBytes() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_UNPACK_LSB_FIRST -> ctx().pixelUnpackState.lsbFirst() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_PACK_ALIGNMENT -> ctx().pixelPackState.alignment();
            case GL11.GL_PACK_ROW_LENGTH -> ctx().pixelPackState.rowLength();
            case GL11.GL_PACK_SKIP_ROWS -> ctx().pixelPackState.skipRows();
            case GL11.GL_PACK_SKIP_PIXELS -> ctx().pixelPackState.skipPixels();
            case GL12.GL_PACK_IMAGE_HEIGHT -> ctx().pixelPackState.imageHeight();
            case GL12.GL_PACK_SKIP_IMAGES -> ctx().pixelPackState.skipImages();
            case GL11.GL_PACK_SWAP_BYTES -> ctx().pixelPackState.swapBytes() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_PACK_LSB_FIRST -> ctx().pixelPackState.lsbFirst() ? GL11.GL_TRUE : GL11.GL_FALSE;

            case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> ctx().lightModel.colorControl;

            case GL14.GL_BLEND_DST_ALPHA -> ctx().blendState.getDstAlpha();
            case GL14.GL_BLEND_DST_RGB -> ctx().blendState.getDstRgb();
            case GL14.GL_BLEND_SRC_ALPHA -> ctx().blendState.getSrcAlpha();
            case GL14.GL_BLEND_SRC_RGB -> ctx().blendState.getSrcRgb();
            case GL14.GL_BLEND_EQUATION -> ctx().blendState.getEquationRgb();
            case GL20.GL_BLEND_EQUATION_ALPHA -> ctx().blendState.getEquationAlpha();

            // Legacy blend-factor aliases (GL_BLEND_SRC/GL_BLEND_DST) differ from the GL14 RGB
            // tokens and are rejected by the core-profile backend; map to the RGB factors.
            case GL11.GL_BLEND_SRC -> ctx().blendState.getSrcRgb();
            case GL11.GL_BLEND_DST -> ctx().blendState.getDstRgb();

            case GL11.GL_LOGIC_OP_MODE -> ctx().logicOpMode.getValue();
            case GL11.GL_DRAW_BUFFER -> ctx().drawBuffer.getValue();

            case GL11.GL_STENCIL_FUNC -> ctx().stencilState.getFuncFront();
            case GL11.GL_STENCIL_REF -> ctx().stencilState.getRefFront();
            case GL11.GL_STENCIL_VALUE_MASK -> ctx().stencilState.getValueMaskFront();
            case GL11.GL_STENCIL_FAIL -> ctx().stencilState.getFailOpFront();
            case GL11.GL_STENCIL_PASS_DEPTH_FAIL -> ctx().stencilState.getZFailOpFront();
            case GL11.GL_STENCIL_PASS_DEPTH_PASS -> ctx().stencilState.getZPassOpFront();
            case GL11.GL_STENCIL_WRITEMASK -> ctx().stencilState.getWriteMaskFront();
            case GL11.GL_STENCIL_CLEAR_VALUE -> ctx().stencilState.getClearValue();

            case GL15.GL_ARRAY_BUFFER_BINDING -> ctx().boundVBO;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING -> ctx().vaos.boundEBO;
            case GL21.GL_PIXEL_UNPACK_BUFFER_BINDING -> ctx().boundPixelUnpackBuffer;
            case GL21.GL_PIXEL_PACK_BUFFER_BINDING -> ctx().boundPixelPackBuffer;

            case GL20.GL_CURRENT_PROGRAM -> ctx().activeProgram;

            case GL30.GL_VERTEX_ARRAY_BINDING -> ctx().boundVAO;

            case GL30.GL_DRAW_FRAMEBUFFER_BINDING -> ctx().drawFramebuffer;
            case GL30.GL_READ_FRAMEBUFFER_BINDING -> ctx().readFramebuffer;

            default -> switch (pname) {
                case GL11.GL_FOG_MODE -> ctx().fogState.getFogMode();
                case GL11.GL_LINE_STIPPLE_PATTERN -> ctx().lineState.getStipplePattern() & 0xFFFF;
                case GL11.GL_LINE_STIPPLE_REPEAT -> ctx().lineState.getStippleFactor();
                case GL11.GL_DEPTH_BITS -> drawFramebufferAttachmentSize(false);
                case GL11.GL_STENCIL_BITS -> drawFramebufferAttachmentSize(true);
                default -> {
                    final int field = VAOManager.clientArrayField(pname, ctx().clientActiveTextureUnit);
                    if (field >= 0 && (field & ~0xFF) != VAOManager.FIELD_POINTER) yield ctx().vaos.clientArrayParam(field);
                    final int desc = LegacyQueries.desc(pname);
                    yield desc != 0 ? LegacyQueries.getInteger(ctx(), pname, desc) : RENDER_BACKEND.getInteger(pname);
                }
            };
        };
    }

    public static void glGetInteger(int pname, IntBuffer params) {
        if (shouldBypassCache()) {
            RENDER_BACKEND.getInteger(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_VIEWPORT -> ctx().viewportState.get(params);
            case GL11.GL_SCISSOR_BOX -> ctx().scissorState.get(params);
            case GL11.GL_POLYGON_MODE -> {
                final int pos = params.position();
                params.put(pos, ctx().polygonState.getFrontMode());
                params.put(pos + 1, ctx().polygonState.getBackMode());
            }
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(params.position(), glGetInteger(pname));
                } else if (!LegacyQueries.getIntegerv(ctx(), pname, params)) {
                    RENDER_BACKEND.getInteger(pname, params);
                }
            }
        }
    }

    /**
     * Array flavour of {@link #glGetInteger(int, IntBuffer)}. Callers whose GL queries GLSM redirects
     * pass arrays, so this overload has to exist.
     *
     * <p>Only the elements the query produces are written, so the tail of a caller array keeps its own
     * values instead of what an earlier query left there: a single-valued pname fills one slot, a
     * tracked vector fills its own width (capped by {@code params.length}), and the driver answers a
     * pname GLSM does not track through a direct scratch cleared before the query.
     */
    public static void glGetInteger(int pname, int[] params) {
        if (params.length == 0) return;
        if (shouldBypassCache()) {
            queryDriverVector(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_VIEWPORT, GL11.GL_SCISSOR_BOX -> copyTrackedVector(pname, 4, params);
            case GL11.GL_POLYGON_MODE -> copyTrackedVector(pname, 2, params);
            default -> {
                if (LegacyQueries.desc(pname) != 0) {
                    LegacyQueries.getIntegerv(ctx(), pname, params);
                } else if (HAS_MULTIPLE_SET.contains(pname)) {
                    queryDriverVector(pname, params);
                } else {
                    params[0] = glGetInteger(pname);
                }
            }
        }
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        if (shouldBypassCache()) {
            RENDER_BACKEND.getFloat(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_MODELVIEW_MATRIX -> ctx().modelViewMatrix.get(params.position(), params);
            case GL11.GL_PROJECTION_MATRIX -> ctx().projectionMatrix.get(params.position(), params);
            case GL11.GL_TEXTURE_MATRIX -> ctx().textures.getTextureUnitMatrix(getActiveTextureUnit()).get(params.position(), params);
            case ARBImaging.GL_COLOR_MATRIX -> ctx().colorMatrix.get(params.position(), params);
            case GL11.GL_COLOR_CLEAR_VALUE -> ctx().clearColor.get(params);
            case GL11.GL_CURRENT_COLOR -> ctx().color.get(params);
            case GL14.GL_CURRENT_SECONDARY_COLOR -> ctx().secondaryColor.get(params);
            case GL11.GL_DEPTH_RANGE -> {
                final int pos = params.position();
                params.put(pos, (float) ctx().viewportState.depthRangeNear);
                params.put(pos + 1, (float) ctx().viewportState.depthRangeFar);
            }
            case GL14.GL_BLEND_COLOR -> {
                final int pos = params.position();
                params.put(pos, ctx().blendState.getBlendColorR());
                params.put(pos + 1, ctx().blendState.getBlendColorG());
                params.put(pos + 2, ctx().blendState.getBlendColorB());
                params.put(pos + 3, ctx().blendState.getBlendColorA());
            }
            case GL11.GL_CURRENT_NORMAL -> {
                final Vector3f normal = ShaderManager.getCurrentNormal();
                final int pos = params.position();
                params.put(pos, normal.x);
                params.put(pos + 1, normal.y);
                params.put(pos + 2, normal.z);
            }
            case GL11.GL_CURRENT_TEXTURE_COORDS -> {
                // Legacy GL reports the coordinates of the active texture unit; unit 1 is the
                // lightmap slot, whose per-vertex value lives in the brightness fields.
                final GLContextState glCtx = ctx();
                final int unit = glCtx.activeTextureUnit.getValue();
                final int pos = params.position();
                if (unit == 1) {
                    params.put(pos, glCtx.lastBrightnessX);
                    params.put(pos + 1, glCtx.lastBrightnessY);
                    params.put(pos + 2, 0.0f);
                    params.put(pos + 3, 1.0f);
                } else if (unit >= 0 && unit < glCtx.ffp.currentTexCoords.length) {
                    final Vector4f tc = glCtx.ffp.currentTexCoords[unit];
                    params.put(pos, tc.x);
                    params.put(pos + 1, tc.y);
                    params.put(pos + 2, tc.z);
                    params.put(pos + 3, tc.w);
                } else {
                    // Units beyond the tracked slots start at the GL initial value.
                    params.put(pos, 0.0f);
                    params.put(pos + 1, 0.0f);
                    params.put(pos + 2, 0.0f);
                    params.put(pos + 3, 1.0f);
                }
            }
            case GL11.GL_FOG_COLOR -> {
                final FloatBuffer fogBuf = ctx().fogState.getFogColorBuffer();
                final int pos = params.position();
                params.put(pos, fogBuf.get(0));
                params.put(pos + 1, fogBuf.get(1));
                params.put(pos + 2, fogBuf.get(2));
                params.put(pos + 3, fogBuf.get(3));
            }
            case GL11.GL_LIGHT_MODEL_AMBIENT -> ctx().lightModel.ambient.get(params.position(), params);
            case GL14.GL_POINT_DISTANCE_ATTENUATION -> {
                final int pos = params.position();
                params.put(pos, ctx().pointState.getAttenuationConstant());
                params.put(pos + 1, ctx().pointState.getAttenuationLinear());
                params.put(pos + 2, ctx().pointState.getAttenuationQuadratic());
            }
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(params.position(), glGetFloat(pname));
                } else {
                    RENDER_BACKEND.getFloat(pname, params);
                }
            }
        }
    }

    /**
     * Array flavour of {@link #glGetFloat(int, FloatBuffer)}. Callers whose GL queries GLSM redirects
     * pass arrays, so this overload has to exist. The scratch buffer is sized for the widest query GLSM
     * answers (a 4x4 matrix) so the backend can never overflow a shorter caller array.
     */
    public static void glGetFloat(int pname, float[] params) {
        if (params.length == 0) return;
        final FloatBuffer buffer = params.length <= ARRAY_QUERY_SCRATCH_SIZE
            ? ARRAY_QUERY_FLOAT_SCRATCH
            : BufferUtils.createFloatBuffer(params.length);
        buffer.clear();
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, 0.0f);
        }
        glGetFloat(pname, buffer);
        buffer.get(0, params, 0, params.length);
    }

    public static double glGetDouble(int pname) {
        return switch (pname) {
            case GL11.GL_DEPTH_CLEAR_VALUE -> ctx().depthState.getClearValue();
            default -> glGetFloat(pname);
        };
    }

    public static void glGetDouble(int pname, DoubleBuffer params) {
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled()) {
            RENDER_BACKEND.getDouble(pname, params);
            return;
        }

        final int pos = params.position();
        switch (pname) {
            case GL11.GL_DEPTH_CLEAR_VALUE -> params.put(pos, glCtx.depthState.getClearValue());
            case GL11.GL_DEPTH_RANGE -> {
                params.put(pos, glCtx.viewportState.depthRangeNear);
                params.put(pos + 1, glCtx.viewportState.depthRangeFar);
            }
            case GL11.GL_MODELVIEW_MATRIX, GL11.GL_PROJECTION_MATRIX, GL11.GL_TEXTURE_MATRIX -> widenFromFloat(pname, params, 16);
            case GL11.GL_COLOR_CLEAR_VALUE, GL11.GL_CURRENT_COLOR, GL11.GL_CURRENT_TEXTURE_COORDS, GL11.GL_FOG_COLOR,
                GL11.GL_LIGHT_MODEL_AMBIENT, GL14.GL_BLEND_COLOR, GL14.GL_CURRENT_SECONDARY_COLOR -> widenFromFloat(pname, params, 4);
            case GL11.GL_CURRENT_NORMAL, GL14.GL_POINT_DISTANCE_ATTENUATION -> widenFromFloat(pname, params, 3);
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(pos, glGetDouble(pname));
                } else {
                    RENDER_BACKEND.getDouble(pname, params);
                }
            }
        }
    }

    public static void glGetDouble(int pname, double[] params) {
        final DoubleBuffer scratch = ctx().queryScratchDouble;
        final DoubleBuffer buf;
        if (params.length <= scratch.capacity()) {
            buf = scratch;
            buf.clear();
            buf.limit(params.length);
        } else {
            buf = BufferUtils.createDoubleBuffer(params.length);
        }
        glGetDouble(pname, buf);
        buf.position(0);
        buf.get(params);
    }

    public static float glGetFloat(int pname) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getFloat(pname);
        }
        if (isEnableCap(pname)) {
            return glIsEnabled(pname) ? 1.0F : 0.0F;
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST_REF -> ctx().alphaState.getReference();
            case GL11.GL_FOG_DENSITY -> ctx().fogState.getDensity();
            case GL11.GL_FOG_START -> ctx().fogState.getStart();
            case GL11.GL_FOG_END -> ctx().fogState.getEnd();
            case GL11.GL_DEPTH_CLEAR_VALUE -> (float) ctx().depthState.getClearValue();
            case GL11.GL_LINE_WIDTH -> ctx().lineState.getWidth();
            case GL11.GL_POINT_SIZE -> ctx().pointState.getSize();
            case GL14.GL_POINT_SIZE_MIN -> ctx().pointState.getMinSize();
            case GL14.GL_POINT_SIZE_MAX -> {
                final GLContextState glCtx = ctx();
                yield Float.isNaN(glCtx.pointState.getMaxSize()) ? maxPointSize(glCtx) : glCtx.pointState.getMaxSize();
            }
            case GL14.GL_POINT_FADE_THRESHOLD_SIZE -> ctx().pointState.getFadeThreshold();
            default -> {
                final int desc = LegacyQueries.desc(pname);
                yield desc != 0 ? LegacyQueries.getFloat(ctx(), pname, desc) : RENDER_BACKEND.getFloat(pname);
            }
        };
    }

    public static int glGetError() {
        if (!RENDER_BACKEND.hasContext()) return 0;
        final GLSMInitConfig config = initConfig;
        if (config != null && config.isNoErrorChecks()) return 0;
        return RENDER_BACKEND.getError();
    }

    public static String glGetString(int pname) {
        if (!RENDER_BACKEND.hasContext()) return "no valid GL/render context";
        return RENDER_BACKEND.getString(pname);
    }

    public static String glGetStringi(int name, int index) {
        if (!RENDER_BACKEND.hasContext()) return "no valid GL/render context";
        return RENDER_BACKEND.getStringi(name, index);
    }

    public static int glGetInteger(int pname, int index) {
        return imageBindingOrBackend(pname, index);
    }

    public static void glGetBoolean(int pname, int index, ByteBuffer params) {
        glGetBooleani_v(pname, index, params);
    }

    public static void checkGLError() {
            final int error = glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new OpenGLException(error);
            }
        }

    public static void glGenQueries(IntBuffer ids) { RENDER_BACKEND.genQueries(ids); }

    public static void glGenQueries(int[] ids) {
            requireContext("glGenQueries");
            for (int i = 0; i < ids.length; i++) ids[i] = RENDER_BACKEND.genQueries();
        }

    public static int glGenQueries() {
            requireContext("glGenQueries");
            return RENDER_BACKEND.genQueries();
        }

    public static int glGetIntegeri(int pname, int index) {
            return imageBindingOrBackend(pname, index);
        }

    public static void glGetIntegeri(int pname, int index, IntBuffer params) {
            glGetIntegeri_v(pname, index, params);
        }

    public static void glGetIntegeri_v(int pname, int index, IntBuffer params) {
            params.put(params.position(), imageBindingOrBackend(pname, index));
        }

    public static void glGetIntegeri_v(int pname, int index, int[] params) {
            params[0] = imageBindingOrBackend(pname, index);
        }

    public static void glGetBooleani_v(int pname, int index, ByteBuffer params) {
            params.put(params.position(), (byte) (imageBindingOrBackend(pname, index) != 0 ? GL11.GL_TRUE : GL11.GL_FALSE));
        }

    public static boolean glGetBooleani(int pname, int index) {
            return imageBindingOrBackend(pname, index) != 0;
        }

    public static void glGetBooleani(int pname, int index, ByteBuffer params) {
            glGetBooleani_v(pname, index, params);
        }
}
